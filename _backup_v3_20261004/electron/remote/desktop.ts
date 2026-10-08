// Remote desktop: screen capture, streaming, and input injection for authenticated mobile clients.
import { desktopCapturer, screen } from 'electron';
import { createHash } from 'node:crypto';

// Lazy load nut.js to avoid loading native bindings at startup
let nutJs: any = null;
async function getNutJs() {
  if (!nutJs) {
    try {
      nutJs = await import('@nut-tree-fork/nut-js');
      // Configure for low latency
      nutJs.mouse.config.autoDelayMs = 0;
      nutJs.mouse.config.mouseSpeed = 1000;
      nutJs.keyboard.config.autoDelayMs = 0;
    } catch (error) {
      console.error('Failed to load nut.js:', error);
      throw new Error('Desktop control not available: ' + (error as Error).message);
    }
  }
  return nutJs;
}

export interface DesktopFrame {
  image: Buffer; // JPEG encoded
  width: number;
  height: number;
  sequence: number;
  timestamp: number;
}

export interface StreamOptions {
  quality: 'auto' | 'low' | 'medium' | 'high';
  maxFps: number;
  maxWidth: number;
  maxHeight: number;
}

export interface CursorPosition {
  x: number;
  y: number;
  screenWidth: number;
  screenHeight: number;
}

export interface InputEvent {
  type: 'move' | 'click' | 'scroll' | 'key' | 'drag';
  x?: number;
  y?: number;
  button?: 'left' | 'right' | 'middle';
  double?: boolean;
  deltaX?: number;
  deltaY?: number;
  key?: string;
  text?: string;
  modifiers?: string[];
  relative?: boolean; // For trackpad mode
  phase?: 'start' | 'move' | 'end';
}

export interface StreamStats {
  fps: number;
  frameCount: number;
  lastFrameTime: number;
  avgFrameInterval: number;
}

export class DesktopCapture {
  private frameWidth = 0;
  private frameHeight = 0;
  mapInput(event: InputEvent): InputEvent {
    if (event.relative || event.x === undefined || event.y === undefined || !this.frameWidth) return event;
    const bounds = screen.getPrimaryDisplay().bounds;
    const dip = { x: bounds.x + Math.min(bounds.width - 1, Math.max(0, Math.round(event.x * bounds.width / this.frameWidth))),
      y: bounds.y + Math.min(bounds.height - 1, Math.max(0, Math.round(event.y * bounds.height / this.frameHeight))) };
    const native = process.platform === 'win32' ? screen.dipToScreenPoint(dip) : dip;
    return { ...event, ...native };
  }
  private capturing = false;
  private sourceId: string | null = null;
  private sequence = 0;
  private lastSentAt = 0;
  private lastFrame: Buffer | null = null;
  private lastFrameHash: string | null = null;
  private frameTimer: NodeJS.Timeout | null = null;
  private subscribers = new Set<(frame: DesktopFrame) => void>();
  private options: StreamOptions = {
    quality: 'auto',
    maxFps: 30,
    maxWidth: 1920,
    maxHeight: 1080,
  };

  async start(opts?: Partial<StreamOptions>): Promise<void> {
    if (this.capturing) { if (opts) this.updateOptions(opts); return; }
    if (opts) this.options = { ...this.options, ...opts };

    // Get primary display source
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: { width: 1920, height: 1080 },
    });

    const primaryDisplay = screen.getPrimaryDisplay();
    const primarySource = sources.find((s) =>
      s.display_id === primaryDisplay.id.toString()
    ) || sources[0];

    if (!primarySource) throw new Error('No screen source available');
    this.sourceId = primarySource.id;
    this.capturing = true;
    this.sequence = 0;

    // Start capture loop
    this.scheduleNextFrame();
  }

  stop(): void {
    this.capturing = false;
    if (this.frameTimer) {
      clearTimeout(this.frameTimer);
      this.frameTimer = null;
    }
    this.subscribers.clear();
    this.lastFrame = null;
    this.lastFrameHash = null;
    this.sourceId = null;
  }

  subscribe(callback: (frame: DesktopFrame) => void): () => void {
    this.subscribers.add(callback);
    return () => this.subscribers.delete(callback);
  }

  updateOptions(opts: Partial<StreamOptions>): void {
    this.options = { ...this.options, ...opts };
  }

  getOptions(): StreamOptions {
    return { ...this.options };
  }

  private scheduleNextFrame(): void {
    if (!this.capturing) return;
    const interval = 1000 / this.options.maxFps;
    this.frameTimer = setTimeout(() => {
      void this.captureFrame().then(() => this.scheduleNextFrame());
    }, interval);
  }

  private async captureFrame(): Promise<void> {
    if (!this.capturing || this.subscribers.size === 0) return;

    try {
      const sources = await desktopCapturer.getSources({
        types: ['screen'],
        thumbnailSize: {
          width: this.options.maxWidth,
          height: this.options.maxHeight,
        },
      });

      const source = sources.find((s) => s.id === this.sourceId);
      if (!source) {
        this.sourceId = sources.find(s => s.display_id === String(screen.getPrimaryDisplay().id))?.id ?? sources[0]?.id ?? null;
        this.lastFrameHash = null;
        return;
      }

      const thumbnail = source.thumbnail;
      if (thumbnail.isEmpty()) throw new Error("Desktop capture returned an empty image");
      const size = thumbnail.getSize();
      
      // Resize if needed
      let image = thumbnail;
      if (size.width > this.options.maxWidth || size.height > this.options.maxHeight) {
        const scale = Math.min(
          this.options.maxWidth / size.width,
          this.options.maxHeight / size.height
        );
        const newWidth = Math.floor(size.width * scale);
        const newHeight = Math.floor(size.height * scale);
        image = thumbnail.resize({ width: newWidth, height: newHeight });
      }

      // Convert to JPEG
      const jpeg = image.toJPEG(this.getQualityLevel());

      // Check if frame changed (avoid sending identical frames)
      const hash = createHash('md5').update(jpeg).digest('hex');
      if (hash === this.lastFrameHash && this.sequence > 0 && Date.now() - this.lastSentAt < 1000) return;
      this.lastSentAt = Date.now();

      this.lastFrameHash = hash;
      this.lastFrame = jpeg;
      this.sequence++;

      const frameSize = image.getSize();
      this.frameWidth = frameSize.width;
      this.frameHeight = frameSize.height;
      const frame: DesktopFrame = {
        image: jpeg,
        width: frameSize.width,
        height: frameSize.height,
        sequence: this.sequence,
        timestamp: Date.now(),
      };

      if (process.env.SWARM_REMOTE_DEBUG === "1" && (this.sequence === 1 || this.sequence % 120 === 0)) console.info("REMOTE_FRAME_CREATED", { width: frame.width, height: frame.height, bytes: jpeg.length });
      // Broadcast to all subscribers
      for (const callback of this.subscribers) {
        try {
          callback(frame);
        } catch (e) {
          console.error('Frame callback error:', e);
        }
      }
    } catch (e) {
      console.error('Capture frame error:', e);
    }
  }

  private getQualityLevel(): number {
    switch (this.options.quality) {
      case 'low': return 50;
      case 'medium': return 70;
      case 'high': return 85;
      case 'auto': return 75;
      default: return 75;
    }
  }

  getCursorPosition(): CursorPosition | null {
    // Electron doesn't provide cursor position directly
    // This would need native module or robotjs
    // For now, return null and rely on client-side cursor rendering
    const display = screen.getPrimaryDisplay();
    return {
      x: 0,
      y: 0,
      screenWidth: display.size.width,
      screenHeight: display.size.height,
    };
  }
}

export class InputInjector {
  private queue: Promise<unknown> = Promise.resolve();
  inject(event: InputEvent): Promise<boolean> {
    const result = this.queue.then(() => this.injectOrdered(event));
    this.queue = result.catch(() => false);
    return result;
  }
  private lastInputTime = 0;
  private inputCount = 0;
  private readonly rateLimitWindow = 1000; // 1 second
  private readonly maxInputsPerWindow = 200; // Increased for smooth cursor movement
  private lastMousePos: { x: number; y: number } | null = null;

  /** Rate-limited input injection - returns false if rate limit exceeded */
  private async injectOrdered(event: InputEvent): Promise<boolean> {
    // Rate limiting
    const now = Date.now();
    if (now - this.lastInputTime > this.rateLimitWindow) {
      this.inputCount = 0;
      this.lastInputTime = now;
    }
    
    if (this.inputCount >= this.maxInputsPerWindow) {
      return false;
    }
    
    this.inputCount++;

    try {
      const nut = await getNutJs();
      const { mouse, keyboard, Point, Button, Key } = nut;

      switch (event.type) {
        case 'move':
          if (event.x !== undefined && event.y !== undefined) {
            if (event.relative) {
              const current = await mouse.getPosition();
              // Relative movement (trackpad mode)
              await mouse.move([
                new Point(
                  current.x + Math.round(event.x),
                  current.y + Math.round(event.y)
                )
              ]);
              this.lastMousePos = await mouse.getPosition();
            } else {
              // Absolute movement
              const target = new Point(Math.round(event.x), Math.round(event.y));
              await mouse.setPosition(target);
              if (process.env.SWARM_REMOTE_DEBUG === '1') console.info('REMOTE_CURSOR_NATIVE', { target, actual: await mouse.getPosition() });
              this.lastMousePos = { x: target.x, y: target.y };
            }
          }
          break;

        case 'click':
          if (event.x !== undefined && event.y !== undefined) {
            // Move to position first
            const pos = new Point(Math.round(event.x), Math.round(event.y));
            await mouse.setPosition(pos);
            this.lastMousePos = { x: pos.x, y: pos.y };
            
            // Perform click
            const button = this.mapButton(event.button, Button);
            if (event.double) {
              await mouse.doubleClick(button);
            } else {
              await mouse.click(button);
            }
          }
          break;

        case 'drag':
          if (event.x !== undefined && event.y !== undefined) {
            const button = this.mapButton(event.button, Button);
            if (!event.phase || event.phase === 'start') await mouse.pressButton(button);
            const pos = new Point(Math.round(event.x), Math.round(event.y));
            await mouse.setPosition(pos);
            if (!event.phase || event.phase === 'end') await mouse.releaseButton(button);
            this.lastMousePos = { x: pos.x, y: pos.y };
          }
          break;

        case 'scroll':
          if (event.deltaY !== undefined) {
            // Normalize scroll amount
            const amount = Math.round(event.deltaY);
            if (amount > 0) await mouse.scrollDown(amount);
            else if (amount < 0) await mouse.scrollUp(-amount);
          }
          break;

        case 'key':
          if (event.text) {
            // Type text directly
            await keyboard.type(event.text);
          } else if (event.key) {
            const nutKey = this.mapKey(event.key, Key);
            
            if (event.modifiers && event.modifiers.length > 0) {
              // Press modifiers
              const modKeys: any[] = [];
              for (const mod of event.modifiers) {
                const modKey = this.mapModifierKey(mod, Key);
                if (modKey) {
                  modKeys.push(modKey);
                  await keyboard.pressKey(modKey);
                }
              }
              
              // Press main key
              await keyboard.type(nutKey);
              
              // Release modifiers
              for (const modKey of modKeys.reverse()) {
                await keyboard.releaseKey(modKey);
              }
            } else {
              // Simple key press
              await keyboard.type(nutKey);
            }
          }
          break;
      }
      return true;
    } catch (e) {
      console.error('Input injection error:', e);
      return false;
    }
  }

  async getCursorPosition(): Promise<CursorPosition> {
    try {
      const nut = await getNutJs();
      const pos = await nut.mouse.getPosition();
      const display = screen.getPrimaryDisplay();
      return {
        x: pos.x,
        y: pos.y,
        screenWidth: display.size.width,
        screenHeight: display.size.height,
      };
    } catch (e) {
      console.error('Get cursor position error:', e);
      const display = screen.getPrimaryDisplay();
      return { x: 0, y: 0, screenWidth: display.size.width, screenHeight: display.size.height };
    }
  }

  private mapButton(button: string | undefined, Button: any): any {
    switch (button) {
      case 'right': return Button.RIGHT;
      case 'middle': return Button.MIDDLE;
      default: return Button.LEFT;
    }
  }

  private mapKey(key: string, Key: any): any {
    // Map common keys to nut.js Key enum
    const keyMap: Record<string, string> = {
      'ctrl': 'LeftControl', 'alt': 'LeftAlt', 'shift': 'LeftShift',
      'del': 'Delete',
      'enter': 'Enter',
      'return': 'Enter',
      'backspace': 'Backspace',
      'delete': 'Delete',
      'tab': 'Tab',
      'escape': 'Escape',
      'esc': 'Escape',
      'up': 'Up',
      'down': 'Down',
      'left': 'Left',
      'right': 'Right',
      'home': 'Home',
      'end': 'End',
      'pageup': 'PageUp',
      'pagedown': 'PageDown',
      'space': 'Space',
      ' ': 'Space',
    };

    const mapped = keyMap[key.toLowerCase()];
    return mapped ? Key[mapped] : key;
  }

  private mapModifierKey(mod: string, Key: any): any {
    const modMap: Record<string, string> = {
      'ctrl': 'LeftControl',
      'control': 'LeftControl',
      'alt': 'LeftAlt',
      'shift': 'LeftShift',
      'meta': 'LeftSuper',
      'command': 'LeftSuper',
      'win': 'LeftSuper',
    };

    const mapped = modMap[mod.toLowerCase()];
    return mapped ? Key[mapped] : null;
  }
}

// Singleton instances
let captureInstance: DesktopCapture | null = null;
let injectorInstance: InputInjector | null = null;

export function getDesktopCapture(): DesktopCapture {
  if (!captureInstance) {
    captureInstance = new DesktopCapture();
  }
  return captureInstance;
}

export function getInputInjector(): InputInjector {
  if (!injectorInstance) {
    injectorInstance = new InputInjector();
  }
  return injectorInstance;
}

export function stopDesktopCapture(): void {
  if (captureInstance) {
    captureInstance.stop();
    captureInstance = null;
  }
}
