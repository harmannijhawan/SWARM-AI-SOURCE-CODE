// HTTPS + WebSocket transport for the SWARM Remote Protocol v1 (docs/REMOTE_PROTOCOL.md).
// `handle()` is transport-agnostic (plain request in, plain response out) so it can be unit-tested without sockets.
import https from 'node:https';
import type { IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocketServer, type WebSocket } from 'ws';
import { Authenticator, RateLimiter, type Headers } from './auth';
import type { StoredDevice, DeviceStore } from './devices';
import { Gateway, GatewayError } from './gateway';
import type { PairingManager } from './pairing';
import { MAX_BODY_BYTES, PROTOCOL_VERSION, WS_PATH, canonicalPair, pairCode, parsePublicKey, verifySignature } from './protocol';
import { classifyRemoteAddress } from './routes';
import type { TlsIdentity } from './cert';

export interface ApiRequest { method: string; url: string; headers: Headers; body: Buffer; ip: string }
export interface ApiResponse { status: number; body: unknown; headers?: Record<string, string> }
export interface PairRequestInfo { deviceId: string; deviceName: string; code: string }

export interface ServerOptions {
  tls: TlsIdentity | null; // null = no listener (handle() only, tests)
  port: number;
  pcName: string;
  devices: DeviceStore;
  pairing: PairingManager;
  gateway: Gateway;
  /** Asks the person at the PC to approve a pairing; resolves true to accept. */
  confirmPair: (info: PairRequestInfo) => Promise<boolean>;
  /** Current candidate endpoints (same list/order as a QR's hosts). Optional; returned as hosts in /v1/status and the WS hello. */
  getHosts?: () => Promise<string[]>;
  /** Called after each successfully authenticated connection (for "which route is active"). */
  onActivity?: (info: { ip: string; via: ReturnType<typeof classifyRemoteAddress>; deviceId: string }) => void;
  now?: () => number;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const json = (status: number, body: unknown, headers?: Record<string, string>): ApiResponse => ({ status, body, headers });
const normIp = (ip: string | undefined) => (ip ?? 'unknown').replace(/^::ffff:/i, '');

interface Conn { ws: WebSocket; device: StoredDevice; lastCtr: number; ids: Set<string>; seq: number; alive: boolean }

export class RemoteServer {
  private server: https.Server | null = null;
  private wss: WebSocketServer | null = null;
  private conns = new Set<Conn>();
  private limiter: RateLimiter;
  private auth: Authenticator;
  private snapshotTimer: NodeJS.Timeout | null = null;
  private lastSnapshot = '';
  private pingTimer: NodeJS.Timeout | null = null;
  private offRevoke: (() => void) | null = null;
  port = 0;

  constructor(private o: ServerOptions) {
    const now = o.now ?? Date.now;
    this.limiter = new RateLimiter(now);
    this.auth = new Authenticator(o.devices, undefined, now);
  }

  get connectionCount() { return this.conns.size; }

  /** Candidate endpoints with a 3 s cap; never throws. */
  private async hosts(): Promise<string[]> {
    if (!this.o.getHosts) return [];
    try {
      const list = await Promise.race([this.o.getHosts(), new Promise<string[]>((r) => setTimeout(() => r([]), 3000))]);
      return Array.isArray(list) ? list.filter((h) => typeof h === 'string') : [];
    } catch { return []; }
  }

  private async statusBody() { return { ...(await this.o.gateway.status()), hosts: await this.hosts() }; }

  // ---------------------------------------------------------------- HTTP API
  async handle(req: ApiRequest): Promise<ApiResponse> {
    const path = req.url.split('?')[0];
    const method = req.method.toUpperCase();
    try {
      if (method === 'GET' && path === '/v1/ping') return json(200, { ok: true, v: PROTOCOL_VERSION });
      const wait = this.limiter.blockedFor(req.ip);
      if (wait) return json(429, { error: 'rate_limited' }, { 'Retry-After': String(wait) });
      if (method === 'POST' && path === '/v1/pair') return await this.pair(req);

      const a = this.auth.authenticate({ method, target: req.url, headers: req.headers, body: req.body });
      if (!a.ok) {
        this.limiter.fail(req.ip);
        return json(401, { error: 'unauthorized' }, { 'WWW-Authenticate': 'Bearer' });
      }
      this.o.onActivity?.({ ip: req.ip, via: classifyRemoteAddress(req.ip), deviceId: a.device.id });
      const g = this.o.gateway;
      if (method === 'GET' && path === '/v1/status') return json(200, await this.statusBody());
      if (method === 'GET' && path === '/v1/agents') return json(200, { agents: await g.listAgents() });
      if (method === 'GET' && path === '/v1/approvals') return json(200, { approvals: await g.listApprovals() });
      let m = /^\/v1\/agents\/([^/]+)\/(start|stop|task)$/.exec(path);
      if (method === 'POST' && m) {
        const id = decodeURIComponent(m[1]);
        const body = this.parseBody(req.body);
        const agent = m[2] === 'start' ? await g.start(id) : m[2] === 'stop' ? await g.stop(id) : await g.task(id, body.text);
        return json(200, { ok: true, agent });
      }
      m = /^\/v1\/approvals\/([^/]+)\/resolve$/.exec(path);
      if (method === 'POST' && m) {
        const body = this.parseBody(req.body);
        await g.resolveApproval(decodeURIComponent(m[1]), body.approved, body.confirm);
        return json(200, { ok: true });
      }
      return json(404, { error: 'not_found' });
    } catch (e) {
      if (e instanceof GatewayError) return json(e.status, { error: e.code, message: e.message === e.code ? undefined : e.message });
      return json(500, { error: 'internal' });
    }
  }

  private parseBody(body: Buffer): Record<string, unknown> {
    if (!body.length) return {};
    try { const v = JSON.parse(body.toString('utf8')); if (v && typeof v === 'object' && !Array.isArray(v)) return v as Record<string, unknown>; } catch { /* fallthrough */ }
    throw new GatewayError(400, 'bad_request', 'invalid JSON body');
  }

  private async pair(req: ApiRequest): Promise<ApiResponse> {
    let b: Record<string, unknown>;
    try { b = this.parseBody(req.body); } catch { this.limiter.fail(req.ip); return json(400, { error: 'bad_request' }); }
    const { token, deviceName, deviceId, publicKey, proof } = b;
    if (typeof token !== 'string' || typeof deviceName !== 'string' || !deviceName.trim() || deviceName.length > 80 || typeof deviceId !== 'string' || !UUID.test(deviceId)
      || typeof publicKey !== 'string' || !parsePublicKey(publicKey) || typeof proof !== 'string') {
      this.limiter.fail(req.ip);
      return json(400, { error: 'bad_request' });
    }
    if (!this.o.pairing.consume(token)) { this.limiter.fail(req.ip); return json(401, { error: 'invalid_token' }); }
    if (!verifySignature(publicKey, canonicalPair(token, deviceId, publicKey), proof)) { this.limiter.fail(req.ip); return json(401, { error: 'invalid_token' }); }
    if (this.o.devices.has(deviceId)) return json(409, { error: 'device_exists' });
    const accepted = await this.o.confirmPair({ deviceId, deviceName: deviceName.trim(), code: pairCode(token, deviceId, publicKey) });
    if (!accepted) return json(403, { error: 'pairing_declined' });
    const { device, secret } = this.o.devices.add({ id: deviceId, name: deviceName.trim(), publicKey });
    this.limiter.success(req.ip);
    this.o.onActivity?.({ ip: req.ip, via: classifyRemoteAddress(req.ip), deviceId: device.id });
    return json(200, { deviceId: device.id, deviceSecret: secret, pcName: this.o.pcName, v: PROTOCOL_VERSION });
  }

  // ---------------------------------------------------------------- lifecycle
  async start(): Promise<number> {
    if (this.server) return this.port;
    if (!this.o.tls) throw new Error('TLS identity required');
    const server = https.createServer({ key: this.o.tls.key, cert: this.o.tls.cert, minVersion: 'TLSv1.2' }, (rq, rs) => { void this.onHttp(rq, rs); });
    server.requestTimeout = 20_000; server.headersTimeout = 10_000; server.keepAliveTimeout = 5_000;
    this.wss = new WebSocketServer({ noServer: true, maxPayload: MAX_BODY_BYTES });
    server.on('upgrade', (rq, socket, head) => { void this.onUpgrade(rq, socket, head); });
    server.on('clientError', (_e, socket) => { socket.destroy(); });
    const listen = (host: string) => new Promise<void>((resolve, reject) => {
      const onErr = (e: Error) => reject(e);
      server.once('error', onErr);
      server.listen({ port: this.o.port, host, ipv6Only: false }, () => { server.off('error', onErr); resolve(); });
    });
    try { await listen('::'); }
    catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'EADDRINUSE') throw e;
      await listen('0.0.0.0');
    }
    this.server = server;
    this.port = (server.address() as { port: number }).port;
    this.offRevoke = this.o.devices.onRevoke((id) => this.kick(id));
    this.pingTimer = setInterval(() => this.heartbeat(), 20_000);
    this.pingTimer.unref?.();
    return this.port;
  }

  async stop() {
    if (this.pingTimer) clearInterval(this.pingTimer);
    if (this.snapshotTimer) clearTimeout(this.snapshotTimer);
    this.pingTimer = this.snapshotTimer = null;
    this.offRevoke?.(); this.offRevoke = null;
    for (const c of this.conns) { try { c.ws.close(1001, 'bridge stopped'); c.ws.terminate(); } catch { /* ignore */ } }
    this.conns.clear();
    this.wss?.close(); this.wss = null;
    const s = this.server; this.server = null;
    if (s) await new Promise<void>((r) => { s.close(() => r()); s.closeAllConnections?.(); });
  }

  private async onHttp(rq: IncomingMessage, rs: import('node:http').ServerResponse) {
    const chunks: Buffer[] = []; let size = 0; let tooBig = false;
    rq.on('data', (c: Buffer) => { size += c.length; if (size > MAX_BODY_BYTES) { tooBig = true; rq.destroy(); } else chunks.push(c); });
    rq.on('error', () => undefined);
    await new Promise<void>((r) => { rq.on('end', r); rq.on('close', r); });
    if (tooBig) { rs.writeHead(413, { 'Content-Type': 'application/json' }); rs.end('{"error":"too_large"}'); return; }
    const res = await this.handle({ method: rq.method ?? 'GET', url: rq.url ?? '/', headers: rq.headers, body: Buffer.concat(chunks), ip: normIp(rq.socket.remoteAddress) });
    const body = JSON.stringify(res.body);
    rs.writeHead(res.status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Length': Buffer.byteLength(body), ...res.headers });
    rs.end(body);
  }

  // ---------------------------------------------------------------- WebSocket
  private reject(socket: Duplex, status: number, text: string, extra = '') {
    socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n${extra}\r\n`);
    socket.destroy();
  }

  private async onUpgrade(rq: IncomingMessage, socket: Duplex, head: Buffer) {
    const ip = normIp(rq.socket.remoteAddress);
    const wait = this.limiter.blockedFor(ip);
    if (wait) return this.reject(socket, 429, 'Too Many Requests', `Retry-After: ${wait}\r\n`);
    if ((rq.url ?? '').split('?')[0] !== WS_PATH || (rq.method ?? 'GET') !== 'GET') return this.reject(socket, 404, 'Not Found');
    const a = this.auth.authenticate({ method: 'GET', target: rq.url ?? WS_PATH, headers: rq.headers, body: Buffer.alloc(0) });
    if (!a.ok) { this.limiter.fail(ip); return this.reject(socket, 401, 'Unauthorized', 'WWW-Authenticate: Bearer\r\n'); }
    if (!this.wss) return this.reject(socket, 503, 'Service Unavailable');
    this.o.onActivity?.({ ip, via: classifyRemoteAddress(ip), deviceId: a.device.id });
    this.wss.handleUpgrade(rq, socket, head, (ws) => this.onSocket(ws, a.device));
  }

  private send(c: Conn, msg: Record<string, unknown>, seq = false) {
    if (c.ws.readyState !== 1) return;
    c.ws.send(JSON.stringify({ v: PROTOCOL_VERSION, ...(seq ? { seq: ++c.seq } : {}), ...msg }));
  }

  private onSocket(ws: WebSocket, device: StoredDevice) {
    const c: Conn = { ws, device, lastCtr: 0, ids: new Set(), seq: 0, alive: true };
    this.conns.add(c);
    ws.on('pong', () => { c.alive = true; });
    ws.on('close', () => { this.conns.delete(c); });
    ws.on('error', () => { this.conns.delete(c); });
    ws.on('message', (data) => { void this.onMessage(c, data.toString()); });
    void (async () => {
      this.send(c, { type: 'hello', pcName: this.o.pcName, time: new Date().toISOString(), hosts: await this.hosts() });
      this.send(c, { type: 'snapshot', ...(await this.o.gateway.snapshot()) }, true);
    })().catch(() => undefined);
  }

  private async onMessage(c: Conn, raw: string) {
    let m: Record<string, unknown>;
    try { m = JSON.parse(raw); if (!m || typeof m !== 'object') throw new Error(); } catch { return this.send(c, { type: 'ack', ok: false, error: 'bad_request' }); }
    if (m.type === 'ping') return this.send(c, { type: 'pong', t: m.t });
    const id = typeof m.id === 'string' && m.id.length > 0 && m.id.length <= 64 ? m.id : null;
    const fail = (error: string) => this.send(c, { type: 'ack', id, ok: false, error });
    if (m.type !== 'cmd' || !id) return fail('bad_request');
    // Replay protection: strictly increasing counter and never-reused ids within a connection.
    if (typeof m.ctr !== 'number' || !Number.isInteger(m.ctr) || m.ctr <= c.lastCtr || c.ids.has(id)) return fail('replay');
    c.lastCtr = m.ctr; c.ids.add(id);
    if (c.ids.size > 1024) c.ids.delete(c.ids.values().next().value as string);
    const a = (m.args ?? {}) as Record<string, unknown>;
    const g = this.o.gateway;
    try {
      let data: unknown;
      switch (m.cmd) {
        case 'status': data = await this.statusBody(); break;
        case 'agents.list': data = { agents: await g.listAgents() }; break;
        case 'agent.start': data = { agent: await g.start(a.agentId as string) }; break;
        case 'agent.stop': data = { agent: await g.stop(a.agentId as string) }; break;
        case 'agent.task': data = { agent: await g.task(a.agentId as string, a.text) }; break;
        case 'approvals.list': data = { approvals: await g.listApprovals() }; break;
        case 'approval.resolve': data = await g.resolveApproval(a.approvalId, a.approved, a.confirm); break;
        case 'invoke': data = await g.invokeChannel(a.channel, a.args); break;
        default: return fail('unknown_command');
      }
      this.send(c, { type: 'ack', id, ok: true, data });
      this.scheduleSnapshot();
    } catch (e) {
      fail(e instanceof GatewayError ? e.code : 'internal');
    }
  }

  private heartbeat() {
    for (const c of this.conns) {
      if (!c.alive) { c.ws.terminate(); this.conns.delete(c); continue; }
      c.alive = false;
      try { c.ws.ping(); } catch { /* ignore */ }
    }
  }

  /** Closes a device's sockets (revocation). */
  kick(deviceId: string) {
    for (const c of [...this.conns]) if (c.device.id === deviceId) { try { c.ws.close(4401, 'revoked'); } catch { /* ignore */ } this.conns.delete(c); }
  }

  /** Coalesced (<= 1/s) snapshot push to every connected phone, only when something changed. */
  scheduleSnapshot() {
    if (this.snapshotTimer || !this.conns.size) return;
    this.snapshotTimer = setTimeout(async () => {
      this.snapshotTimer = null;
      if (!this.conns.size) return;
      try {
        const s = await this.o.gateway.snapshot();
        const key = JSON.stringify(s, (k, v) => (k === 'updatedAt' ? undefined : v));
        if (key === this.lastSnapshot) return;
        this.lastSnapshot = key;
        for (const c of this.conns) this.send(c, { type: 'snapshot', ...s }, true);
      } catch { /* ignore */ }
    }, 1000);
  }

  /** Forward an app event (already sanitised) to phones and refresh the snapshot. */
  publishEvent(event: Record<string, unknown>) {
    if (!this.conns.size) return;
    for (const c of this.conns) this.send(c, { type: 'event', event }, true);
    this.scheduleSnapshot();
  }
}