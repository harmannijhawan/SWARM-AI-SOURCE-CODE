// Explicit --remote-self-test diagnostic: isolated pairing, real capture and OS input.
import { app, BrowserWindow, nativeImage, screen } from 'electron';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import https from 'node:https';
import { loadOrCreateCert } from './cert';
import { DeviceStore } from './devices';
import { Gateway } from './gateway';
import { PairingManager } from './pairing';
import { RemoteServer } from './server';
import { FakePhone } from '../../tests/helpers/remoteClient';
import { getInputInjector } from './desktop';

export async function remoteSelfTest() {
  const dir = mkdtempSync(path.join(tmpdir(), 'swarm-remote-e2e-'));
  const report: Record<string, unknown> = { directory: dir, packaged: app.isPackaged };
  let ws: ReturnType<FakePhone['ws']> | undefined;
  let target: BrowserWindow | undefined;
  const tls = await loadOrCreateCert(path.join(dir, 'tls.json'));
  const pairing = new PairingManager();
  const srv = new RemoteServer({ tls, port: 0, pcName: 'Remote diagnostic',
    devices: new DeviceStore(path.join(dir, 'devices.json')), pairing,
    gateway: new Gateway(async () => [], { pcName: 'Diagnostic', appVersion: 'test' }), confirmPair: async () => true });
  try {
    const port = await srv.start();
    report.port = port;
    // Test the actual IPv6 socket, TLS identity and health response (not IPv4 loopback).
    report.ipv6Health = await new Promise((resolve, reject) => {
      const req = https.get({ hostname: '::1', port, path: '/v1/ping', rejectUnauthorized: false }, res => {
        let body = ''; res.on('data', d => body += d); res.on('end', () => resolve({ code: res.statusCode, body }));
      }); req.on('error', reject);
    });
    const phone = new FakePhone(port, tls.fingerprint);
    const paired = await phone.request('POST', '/v1/pair', { body: phone.pairBody(pairing.generate().token) });
    if (paired.status !== 200) throw new Error(`Pairing failed ${paired.status}`);
    phone.secret = paired.json.deviceSecret;
    report.authentication = (await phone.request('GET', '/v1/status', { signed: true })).status;
    ws = phone.ws();
    const messages: any[] = [];
    const frames: Buffer[] = [];
    ws.on('message', (data, binary) => { if (binary) frames.push(Buffer.from(data as Buffer)); else messages.push(JSON.parse(data.toString())); });
    const wait = async (predicate: () => boolean, ms = 10000) => {
      const until = Date.now() + ms;
      while (!predicate()) { if (Date.now() > until) throw new Error('Diagnostic timeout'); await new Promise(r => setTimeout(r, 25)); }
    };
    await wait(() => messages.some(m => m.type === 'hello'));
    let counter = 0;
    const command = async (cmd: string, args: unknown = {}) => {
      const id = `test-${++counter}`;
      ws!.send(JSON.stringify({ type: 'cmd', id, ctr: counter, cmd, args }));
      await wait(() => messages.some(m => m.id === id));
      const ack = messages.find(m => m.id === id);
      if (!ack.ok || ack.data?.ok === false) throw new Error(`${cmd} failed: ${JSON.stringify(ack)}`);
      return ack;
    };
    await command('desktop.start', { maxWidth: 1280, maxHeight: 720, maxFps: 15 });
    await wait(() => frames.length > 0);
    const frame = frames[0];
    if (frame.toString('ascii', 0, 4) !== 'SWDF' || frame.readUInt32BE(24) !== frame.length - 28) throw new Error('Invalid binary frame');
    const image = nativeImage.createFromBuffer(frame.subarray(28));
    if (image.isEmpty() || image.getSize().width !== frame.readUInt16BE(12)) throw new Error('JPEG decode failed');
    writeFileSync(path.join(dir, 'desktop.jpg'), frame.subarray(28));
    report.frame = { size: frame.length, width: image.getSize().width, height: image.getSize().height };
    await wait(() => frames.length >= 2, 5000); // new subscribers and static desktops receive fresh frames.
    target = new BrowserWindow({ width: 640, height: 420, title: 'SWARM native input diagnostic', webPreferences: { sandbox: true } });
    await target.loadURL('data:text/html,' + encodeURIComponent(`<textarea autofocus style="position:fixed;inset:0;width:100%;height:100%"></textarea><script>window.events=[];for(const t of ['mousedown','mouseup','dblclick','contextmenu','wheel','keydown'])document.addEventListener(t,e=>{events.push({type:t,key:e.key,button:e.button,ctrl:e.ctrlKey,alt:e.altKey,shift:e.shiftKey});if(t==='contextmenu')e.preventDefault()})</script>`));
    target.setAlwaysOnTop(true);
    target.show(); app.focus({ steal: true }); target.focus();
    await new Promise(r => setTimeout(r, 500));
    const initial = await getInputInjector().getCursorPosition();
    const bounds = target.getBounds();
    report.target = { bounds, visible: target.isVisible(), focused: target.isFocused() };
    if (!target.isFocused()) throw new Error('Diagnostic window could not obtain focus; input test stopped');
    const display = screen.getPrimaryDisplay().bounds;
    const x = Math.round((bounds.x + 220 - display.x) * image.getSize().width / display.width);
    const y = Math.round((bounds.y + 170 - display.y) * image.getSize().height / display.height);
    const input = (args: unknown) => command('desktop.input', args);
    await input({ type: 'move', x, y });
    const moved = await getInputInjector().getCursorPosition();
    const expected = process.platform === 'win32' ? screen.dipToScreenPoint({ x: bounds.x + 220, y: bounds.y + 170 }) : { x: bounds.x + 220, y: bounds.y + 170 };
    report.cursor = { moved, expected };
    if (Math.abs(moved.x - expected.x) > 4 || Math.abs(moved.y - expected.y) > 4) throw new Error('OS cursor did not move to mapped desktop coordinate');
    await input({ type: 'click', x, y });
    await input({ type: 'click', x, y, double: true });
    await input({ type: 'click', x, y, button: 'right' });
    for (const phase of ['start', 'move', 'end']) await input({ type: 'drag', x: x + 10, y, phase });
    await input({ type: 'scroll', deltaY: -1 });
    await input({ type: 'scroll', deltaY: 1 });
    await input({ type: 'key', text: 'swarm-input-test' });
    for (const key of ['enter', 'backspace', 'escape', 'ctrl', 'alt', 'shift']) await input({ type: 'key', key });
    await input({ type: 'key', key: 'a', modifiers: ['ctrl'] });
    await new Promise(r => setTimeout(r, 300));
    const received = await target.webContents.executeJavaScript('({text:document.querySelector("textarea").value,events:window.events})');
    report.input = received;
    if (!received.text.includes('swarm-input-test') || !received.events.some((e: any) => e.type === 'dblclick') || !received.events.some((e: any) => e.type === 'wheel')) throw new Error('OS input not received by target window');
    report.input = received;
    await input({ type: 'move', relative: true, x: 5, y: 4 });
    await command('desktop.stop');
    // Restore the cursor through native input without capture scaling.
    await getInputInjector().inject({ type: 'move', x: initial.x, y: initial.y });
    report.ok = true;
  } catch (error) { report.ok = false; report.error = String((error as Error).stack ?? error);
  } finally {
    ws?.terminate(); target?.destroy(); await srv.stop();
    writeFileSync(path.join(dir, 'report.json'), JSON.stringify(report, null, 2));
  }
  console.log('REMOTE_SELF_TEST', JSON.stringify(report));
  return report.ok === true;
}
