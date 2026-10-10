import {scoreModel} from '../../shared/model-score';
export {scoreModel} from '../../shared/model-score';
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
  return (await import('../account/managed')).managedComplete(opts);
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
