// Model router: scores every eligible model for a task, then executes with an
// automatic, dynamically generated fallback chain across models and providers.
import type { AgentRole, Capability, ModelInfo, UsageRecord } from '../../shared/types';
import type { Settings } from '../../shared/settings';
import { timeOperation, recordTiming } from '../core/performance';
import { db } from '../core/db';
import { emit, type EventScope } from '../core/bus';
import { getSettings } from '../core/settings';
import { notify } from '../core/notify';
import { CancelledError, errMsg, uid } from '../core/util';
import { adapter, getRegistryRevision, listModels, providerConfig, recordFailure, recordSuccess, usable } from '../providers/registry';
import { estimatePromptTokens } from '../providers/http';
import { ProviderError, type ChatMessage, type ChatResult, type ProviderErrorKind } from '../providers/types';

export type Purpose = 'plan' | 'research' | 'design' | 'architecture' | 'code' | 'review' | 'vision' | 'classify' | 'summarize' | 'test';

export interface RouteRequest {
  purpose: Purpose;
  role?: AgentRole;
  needs?: Capability[];
  promptTokens: number;
  maxTokens: number;
  pinned?: string | null;
  exclude?: Set<string>;
  complexity?: 'small' | 'normal' | 'complex';
  escalation?: number;
}

interface Weights { size: number; reliability: number; latency: number; local: number; cloud: number }

const PURPOSE_CAPS: Record<Purpose, Capability[]> = {
  plan: ['reasoning', 'long_context'],
  research: ['long_context', 'reasoning'],
  design: ['reasoning'],
  architecture: ['reasoning', 'coding'],
  code: ['coding', 'long_context'],
  review: ['coding', 'reasoning'],
  vision: ['vision'],
  classify: ['fast'],
  summarize: ['fast', 'long_context'],
  test: ['coding'],
};
const HEAVY: Purpose[] = ['plan', 'architecture', 'code', 'review', 'design'];

function weights(s: Settings): Weights {
  switch (s.ai.routing) {
    case 'local_first': return { size: 1, reliability: 1, latency: 1, local: 60, cloud: 0 };
    case 'cloud_first': return { size: 1, reliability: 1, latency: 1, local: -40, cloud: 10 };
    case 'fastest': return { size: 0.3, reliability: 1, latency: 4, local: 0, cloud: 0 };
    case 'quality': return { size: 2, reliability: 1.2, latency: 0.3, local: -10, cloud: 5 };
    default: return { size: 1, reliability: 1, latency: 1, local: 0, cloud: 0 };
  }
}

export function scoreModel(m: ModelInfo, req: RouteRequest, s: Settings): number {
  const w = weights(s);
  let score = 0;
  const caps = new Set(m.capabilities);
  for (const c of PURPOSE_CAPS[req.purpose]) if (caps.has(c)) score += 25;
  const heavy = HEAVY.includes(req.purpose);
  const size = m.paramsB ?? (m.providerId === 'ollama' ? 7 : 30);
  if (req.complexity === 'small' && !(req.escalation ?? 0) && s.ai.routing === 'auto') {
    // Prefer efficient capable models, still weighted by observed reliability below.
    score += Math.max(0, 48 - Math.log2(Math.max(size, 1)) * 8);
  } else if (heavy) {
    score += Math.min(Math.log2(Math.max(size, 1)) * 7, 70) * w.size;
    if (size < 13 && s.ai.routing === 'auto') score -= 25; // keep small models for light work
  } else if (req.purpose === 'classify' || req.purpose === 'summarize') {
    score += Math.max(0, 30 - Math.log2(Math.max(size, 1)) * 4);
  } else {
    score += Math.min(Math.log2(Math.max(size, 1)) * 4, 40) * w.size;
  }
  // Measured reliability (Laplace smoothed) and latency.
  const rel = (m.successes + 1) / (m.calls + 2);
  score += rel * 40 * w.reliability;
  if (m.consecutiveFailures) score -= m.consecutiveFailures * 12;
  // Expected wall time for this request from measured time-to-first-token and throughput.
  // Unmeasured models get a size-based throughput prior (very large models are usually slower).
  {
    const expectedTokens = Math.min(req.maxTokens, heavy ? 2500 : 700);
    const tpsPrior = size <= 30 ? 60 : size <= 150 ? 35 : 18;
    const secs = (m.latencyMs ?? 3000) / 1000 + expectedTokens / Math.max(4, m.tokensPerSec ?? tpsPrior);
    score -= Math.min(secs, 150) * 0.6 * w.latency;
  }
  if (m.health === 'healthy') score += 45;
  else if (m.health === 'degraded') score -= 20;
  else if (m.health === 'offline') score -= 60;
  if (m.providerId === 'ollama') score += w.local; else score += w.cloud;
  // Context headroom.
  if (m.contextLength < req.promptTokens + req.maxTokens) score -= 80;
  // Per-role preference from settings.
  if (req.role && s.agents.modelPreference[req.role] === m.id) score += 1000;
  if (req.pinned && req.pinned === m.id) score += 5000;
  return score;
}

const routeCache = new Map<string, { expires: number; ranks: { model: ModelInfo; score: number }[] }>();
export function rankModels(req: RouteRequest, s = getSettings()): { model: ModelInfo; score: number }[] {
  const end = timeOperation('route');
  const key = JSON.stringify([getRegistryRevision(), req.purpose, req.role, req.needs, req.promptTokens, req.maxTokens, req.complexity, req.escalation, req.pinned, s.ai.routing, s.ai.freeMode, s.providers.enabled, s.routing.excluded, s.agents.modelPreference]);
  const cached = routeCache.get(key);
  let ranks = cached && cached.expires > Date.now() ? cached.ranks : null;
  if (!ranks) ranks = listModels()
    .filter((m) => usable(m))
    .filter((m) => (req.needs ?? []).every((c) => m.capabilities.includes(c)))
    .filter((m) => m.contextLength >= req.promptTokens + Math.min(req.maxTokens, m.maxOutput ?? req.maxTokens, 1024) + 64)
    .map((model) => ({ model, score: scoreModel(model, req, s) }))
    .sort((a, b) => b.score - a.score);
  if (!cached || cached.expires <= Date.now()) {
    if (routeCache.size >= 128) routeCache.clear();
    routeCache.set(key, { expires: Date.now() + 1000, ranks });
  }
  // Eligibility is checked again because rate-limit cooldowns expire with time.
  const result = ranks.filter(r => !req.exclude?.has(r.model.id) && usable(r.model))
    .map(r => ({ ...r, score: r.score - (r.model.id === req.pinned ? 0 : (inflight.get(r.model.providerId) ?? 0) * 25) }))
    .sort((a, b) => b.score - a.score);
  end();
  return result;
}

// Per-provider in-flight limits (connection reuse is handled by undici keep-alive).
const inflight = new Map<string, number>();
const waiters = new Map<string, (() => void)[]>();
const LIMITS: Record<string, number> = { ollama: 1, nvidia: 4, openrouter: 3, groq: 4, google: 3, cloudflare: 3, huggingface: 3, cerebras: 3, mistral: 2 };
async function acquire(provider: string, signal: AbortSignal) {
  if (signal.aborted) throw new CancelledError();
  const limit = LIMITS[provider] ?? 3;
  while ((inflight.get(provider) ?? 0) >= limit) {
    await new Promise<void>((resolve, reject) => {
      const list = waiters.get(provider) ?? [];
      const fn = () => { signal.removeEventListener('abort', onAbort); resolve(); };
      const onAbort = () => { const l = waiters.get(provider) ?? []; waiters.set(provider, l.filter((x) => x !== fn)); reject(new CancelledError()); };
      signal.addEventListener('abort', onAbort, { once: true });
      list.push(fn); waiters.set(provider, list);
    });
  }
  if (signal.aborted) throw new CancelledError();
  inflight.set(provider, (inflight.get(provider) ?? 0) + 1);
}
function release(provider: string) {
  inflight.set(provider, Math.max(0, (inflight.get(provider) ?? 1) - 1));
  const next = waiters.get(provider)?.shift();
  next?.();
}

export interface CompleteOptions<T> {
  /** Per-request experiment only; never written to user settings. */
  routingOverride?: Settings['ai']['routing'];
  isolatedExperiment?: boolean;
  route: Omit<RouteRequest, 'promptTokens' | 'maxTokens'> & { maxTokens?: number };
  messages: ChatMessage[];
  scope: EventScope;
  signal: AbortSignal;
  temperature?: number;
  quiet?: boolean;
  onReset?: () => void;
  stream?: boolean;
  json?: boolean;
  onToken?: (delta: string) => void;
  /** Validate/parse the output. Throwing marks the output malformed and triggers fallback. */
  validate?: (text: string, result: ChatResult) => T;
  cacheKey?: string;
  onRequest?: (model: ModelInfo) => void;
  onAttempt?: (info: { model: ModelInfo; ok: boolean; error?: string; kind?: ProviderErrorKind }) => void;
}

export interface CompleteResult<T> {
  text: string;
  value: T;
  model: ModelInfo;
  result: ChatResult;
  attempts: { modelId: string; ok: boolean; error?: string }[];
  recovered: boolean;
}

export class NoModelError extends Error {
  constructor(msg: string) { super(msg); this.name = 'NoModelError'; }
}

export async function complete<T = string>(opts: CompleteOptions<T>): Promise<CompleteResult<T>> {
  const currentSettings = getSettings();
  const s = opts.routingOverride ? { ...currentSettings, ai: { ...currentSettings.ai, routing: opts.routingOverride } } : currentSettings;
  const maxTokens = opts.route.maxTokens ?? s.ai.maxOutputTokens;
  const promptTokens = estimatePromptTokens(opts.messages);
  const exclude = new Set(opts.route.exclude ?? []);
  const skipProviders = new Set<string>();
  const attempts: CompleteResult<T>['attempts'] = [];
  const maxAttempts = s.routing.maxFallbacks + 1;
  const pinned = opts.isolatedExperiment ? null : opts.route.pinned ?? s.ai.pinnedModel;

  if (opts.cacheKey && s.performance.cacheModelResults) {
    const hit = db().cacheGet('model:' + opts.cacheKey, 7 * 24 * 3600_000);
    if (hit) {
      const cached = JSON.parse(hit) as { text: string; modelId: string };
      const model = listModels().find((m) => m.id === cached.modelId);
      if (model) {
        const value = opts.validate ? opts.validate(cached.text, { text: cached.text, promptTokens: 0, completionTokens: 0, finishReason: 'stop', latencyMs: 0, ttftMs: 0, usageEstimated: true }) : (cached.text as T);
        emit('MODEL_COMPLETED', `Reused cached result from ${model.displayName}`, opts.scope, 'debug', { modelId: model.id, cached: true });
        return { text: cached.text, value, model, result: { text: cached.text, promptTokens: 0, completionTokens: 0, finishReason: 'stop', latencyMs: 0, ttftMs: 0, usageEstimated: true }, attempts: [], recovered: false };
      }
    }
  }

  let previous: ModelInfo | null = null;
  let lastReason = '';
  let failedAt = 0;
  for (let i = 0; i < maxAttempts; i++) {
    if (opts.signal.aborted) throw new CancelledError();
    const endRoute = timeOperation('route', opts.scope.runId ?? undefined);
    const ranked = rankModels({ ...opts.route, escalation: i, promptTokens, maxTokens, pinned, exclude }, s)
      .filter((r) => !skipProviders.has(r.model.providerId));
    const pick = ranked[0]?.model;
    endRoute();
    if (!pick) {
      const reason = attempts.length
        ? `All eligible models failed (${attempts.length} attempts). Last error: ${lastReason}`
        : `No eligible free model with required capabilities (${[...(opts.route.needs ?? [])].join(', ') || opts.route.purpose}). Connect a provider or start Ollama.`;
      throw new NoModelError(reason);
    }
    exclude.add(pick.id);
    if (previous) {
      recordTiming('fallback', Date.now() - failedAt, opts.scope.runId ?? undefined);
      emit('MODEL_FALLBACK', `Recovered automatically: switching from ${previous.displayName} to ${pick.displayName}`, opts.scope, 'warning', { from: previous.id, to: pick.id, reason: lastReason });
      if (!opts.quiet) notify('fallback', 'warning', 'Model fallback', `${previous.displayName} → ${pick.displayName}`, opts.scope);
    } else {
      emit('MODEL_SELECTED', `Routed to ${pick.displayName} (${pick.providerId})`, opts.scope, 'info', { modelId: pick.id, purpose: opts.route.purpose, candidates: ranked.length, score: Math.round(ranked[0].score) });
    }
    const a = adapter(pick.providerId)!;
    const local = a.kind === 'local';
    const started = Date.now();
    const endRequest = timeOperation('model', opts.scope.runId ?? undefined);
    try {
      await acquire(pick.providerId, opts.signal);
      let result: ChatResult;
      try {
        opts.onRequest?.(pick);
        result = await a.chat(providerConfig(pick.providerId), pick.modelId, {
          messages: opts.messages,
          maxTokens: Math.min(maxTokens, pick.maxOutput ?? maxTokens, Math.max(512, pick.contextLength - promptTokens - 64)),
          temperature: opts.temperature ?? s.ai.temperature,
          signal: opts.signal,
          stream: opts.stream ?? s.performance.streaming,
          json: opts.json,
          onToken: opts.onToken,
          firstTokenTimeoutMs: Math.min(s.routing.firstTokenTimeoutSec * 1000 * (local ? 2 : 1), pick.latencyMs === null || pick.successes < 5 ? Infinity : Math.max(15000, pick.latencyMs * 4 + 5000)),
          totalTimeoutMs: s.routing.requestTimeoutSec * 1000,
        });
      } finally { release(pick.providerId); }
      if (!result.text.trim()) throw new ProviderError('invalid', 'Empty response');
      let value: T;
      try { value = opts.validate ? opts.validate(result.text, result) : (result.text as T); }
      catch (ve) { throw new ProviderError('invalid', `Malformed output: ${errMsg(ve)}`); }
      if (!opts.isolatedExperiment) recordSuccess(pick.id, result.latencyMs, result.ttftMs, result.completionTokens);
      recordUsage(opts.scope, pick, result, true, null, local);
      attempts.push({ modelId: pick.id, ok: true });
      opts.onAttempt?.({ model: pick, ok: true });
      emit('MODEL_COMPLETED', `${pick.displayName} responded in ${(result.latencyMs / 1000).toFixed(1)}s`, opts.scope, 'debug', {
        modelId: pick.id, latencyMs: result.latencyMs, ttftMs: result.ttftMs, promptTokens: result.promptTokens, completionTokens: result.completionTokens, finish: result.finishReason,
      });
      if (opts.cacheKey && s.performance.cacheModelResults) db().cacheSet('model:' + opts.cacheKey, JSON.stringify({ text: result.text, modelId: pick.id }));
      return { text: result.text, value, model: pick, result, attempts, recovered: attempts.length > 1 };
    } catch (e) {
      if (e instanceof CancelledError || (e instanceof ProviderError && e.kind === 'cancelled') || opts.signal.aborted) throw new CancelledError();
      const pe = e instanceof ProviderError ? e : new ProviderError('network', errMsg(e));
      lastReason = `${pe.kind}: ${pe.message}`.slice(0, 300);
      if (!opts.isolatedExperiment || ['rate_limit', 'quota', 'auth'].includes(pe.kind)) recordFailure(pick.id, pe.kind, pe.message, pe.retryAfterMs);
      recordUsage(opts.scope, pick, null, false, lastReason, local, Date.now() - started);
      attempts.push({ modelId: pick.id, ok: false, error: lastReason });
      opts.onReset?.();
      opts.onAttempt?.({ model: pick, ok: false, error: lastReason, kind: pe.kind });
      emit('MODEL_ERROR', `${pick.displayName}: ${describeKind(pe.kind)}`, opts.scope, 'debug', { modelId: pick.id, kind: pe.kind, error: pe.message.slice(0, 500) });
      // Provider-wide failures: skip remaining models from the same provider for this request.
      if (pe.kind === 'auth' || pe.kind === 'network' || pe.kind === 'quota') skipProviders.add(pick.providerId);
      previous = pick;
      failedAt = Date.now();
    } finally { endRequest(); }
  }
  throw new NoModelError(`Exhausted ${maxAttempts} model attempts. Last error: ${lastReason}`);
}

function describeKind(k: ProviderErrorKind): string {
  return ({
    rate_limit: 'rate limited', auth: 'authentication failed', not_found: 'model not found', timeout: 'timed out', server: 'provider error',
    bad_request: 'request rejected', context: 'context overflow', network: 'network error', invalid: 'malformed output', cancelled: 'cancelled',
    quota: 'quota exhausted', unsupported: 'unsupported',
  } as Record<ProviderErrorKind, string>)[k];
}

function recordUsage(scope: EventScope, m: ModelInfo, r: ChatResult | null, ok: boolean, error: string | null, local: boolean, latency?: number) {
  const u: UsageRecord = {
    id: uid('us_'), ts: Date.now(), projectId: scope.projectId ?? null, runId: scope.runId ?? null, agent: scope.agent ?? null,
    providerId: m.providerId, modelId: m.modelId, promptTokens: r?.promptTokens ?? 0, completionTokens: r?.completionTokens ?? 0,
    latencyMs: r?.latencyMs ?? latency ?? 0, ok, error, costUsd: 0, local,
  };
  db().put('usage', u.id, u, { project_id: u.projectId, run_id: u.runId, ts: u.ts, provider_id: u.providerId, model_id: u.modelId });
}
