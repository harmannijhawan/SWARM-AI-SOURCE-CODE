import { complete, rankModels, type Purpose } from '../router/router';
import { estimatePromptTokens } from '../providers/http';
import type { ChatMessage } from '../providers/types';

export function chatPurpose(text: string): Purpose {
  if (/research|latest|current|sources/i.test(text)) return 'research';
  if (/architect|trade.?off|prove|complex|reason step|distributed/i.test(text)) return 'architecture';
  if (/code|function|python|javascript|typescript|debug|error|async|scraper|```/i.test(text)) return 'code';
  if (text.length > 1500) return 'plan';
  return 'classify';
}

// Chat shares eligibility, health, cooldowns, accounting and fallback with Build.
// It has no dependency on projects, agents, processes or browsers.
export class ChatRouter {
  async respond(messages: ChatMessage[], text: string, signal: AbortSignal, onToken: (s: string) => void, onReset: () => void, pinned?: string) {
    const purpose = chatPurpose(text);
    const maxTokens = 4096;
    const full = rankModels({ purpose, promptTokens: estimatePromptTokens(messages) + maxTokens, maxTokens, pinned: pinned ?? '' });
    const candidates = full.length ? full : rankModels({ purpose, promptTokens: 1, maxTokens, pinned: pinned ?? '' });
    if (!candidates.length) throw new Error('No eligible model is available. Connect a provider in Settings or start Ollama.');
    const budget = Math.max(512, candidates[0].model.contextLength - maxTokens - 256);
    const context = [...messages];
    let omitted = 0;
    // Preserve the system instruction and complete recent exchanges. Never silently clip code.
    while (estimatePromptTokens(context) > budget && context.length > 2) {
      context.splice(1, 1); omitted++;
      if (context[1]?.role === 'assistant') { context.splice(1, 1); omitted++; }
    }
    if (estimatePromptTokens(context) > budget) throw new Error('This message exceeds the available model context. Shorten it or choose a larger-context model.');
    const result = await complete({ route: { purpose, maxTokens, pinned: pinned ?? '' }, messages: context, signal, scope: {}, quiet: true, stream: true, onToken, onReset });
    return { ...result, omitted, purpose };
  }
}
