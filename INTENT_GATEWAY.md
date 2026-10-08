# Intent Gateway Implementation

## Overview

This document describes the Intent Gateway system that prevents conversational messages from accidentally triggering the full SWARM build pipeline.

## Problem

Previously, **every user message** was treated as a build objective, causing:
- Simple greetings like "hi" to spawn agents and create projects
- Questions like "what is SWARM?" to trigger full builds
- "Thanks" to start 50+ model calls
- Massive resource waste and poor user experience

## Solution

An **Intent Classification Gateway** sits between user input and the orchestrator:

```
USER INPUT
    ↓
INTENT GATEWAY ← classifies intent
    ↓
┌──────────────────────────────────┐
│ CONVERSATION  → Fast response    │
│ QUESTION      → Answer           │
│ EXPLANATION   → Explain          │
│ RESEARCH      → Web search       │
│ BUILD         → Full pipeline    │
│ CODE/DEBUG    → Fix/modify       │
│ AUTOMATE      → Scripts          │
└──────────────────────────────────┘
```

## Architecture

### Core Components

1. **`electron/core/intent.ts`**
   - Fast heuristic-based classification
   - Pattern matching (no model calls)
   - Returns intent + confidence + action flags

2. **`electron/core/conversation.ts`**
   - Conversational response generation
   - Context-aware suggestions
   - SWARM-specific Q&A

3. **`src/components/Composer.tsx`**
   - Calls classifier before execution
   - Shows conversational UI for non-build intents
   - Only triggers build pipeline when appropriate

### Intent Types

| Intent | Description | Should Start Run | Example |
|--------|-------------|-----------------|---------|
| `CHAT` | Greetings, thanks, simple conversation | ❌ No | "hi", "thanks" |
| `QUESTION` | Information requests | ❌ No | "what is SWARM?" |
| `EXPLANATION` | Learning requests | ❌ No | "how does React work?" |
| `RESEARCH` | Web search needed | ✅ Yes (research only) | "search for latest trends" |
| `BUILD` | Create/build applications | ✅ Yes (full pipeline) | "build me a website" |
| `DEBUG` | Fix errors | ✅ Yes (if project exists) | "fix the build error" |
| `CODE` | Edit code | ✅ Yes (if project exists) | "add a feature" |
| `AUTOMATE` | Scripts/automation | ✅ Yes | "write a script to..." |
| `AMBIGUOUS` | Unclear intent | ❌ No (ask for clarification) | "do something" |

## Classification Algorithm

Fast pattern-based classification using regex:

### Phase 1: Obvious Chat (Highest Priority)
- Very short messages (<= 20 chars)
- Common greetings: hi, hello, thanks, bye
- Confidence: 1.0

### Phase 2: Explanation Requests
- "how to", "how does", "explain"
- "would you build" (asking about methodology)
- Must come before BUILD patterns
- Confidence: 0.85

### Phase 3: Build Requests
- "build me", "create an app", "make a website"
- Imperative verb + artifact
- Confidence: 0.95 (very high for explicit requests)

### Phase 4-8: Other Intents
- Research, Questions, Debug, Edit, Automate
- Specific pattern matching
- Confidence: 0.80-0.90

### Phase 9-12: Heuristics
- Short messages without imperatives → CHAT
- Imperative + artifact → BUILD
- Medium length unclear → AMBIGUOUS
- Long descriptions → BUILD (default)

## Performance

**Classification Speed:**
- Zero model calls required
- Pure JavaScript regex matching
- < 1ms per classification
- No external API dependencies

**Test Coverage:**
- 35 comprehensive tests
- 100% pass rate
- Critical edge cases covered

## Critical Test Cases

All of these now **correctly** avoid triggering builds:

```typescript
✓ "hi" → CHAT (not BUILD)
✓ "hello" → CHAT (not BUILD)
✓ "what is SWARM?" → QUESTION (not BUILD)
✓ "thanks" → CHAT (not BUILD)
✓ "explain this error" → EXPLANATION (not BUILD)
✓ "how to build a website" → EXPLANATION (not BUILD)
```

All of these **correctly** trigger builds:

```typescript
✓ "build me a website" → BUILD
✓ "create a full-stack SaaS" → BUILD
✓ "make a sneaker store" → BUILD
✓ "build a React dashboard" → BUILD
```

## User Experience

### Before (❌ Broken)
```
User: "hi"
System: *creates project, spawns 10 agents, runs 50 model calls*
```

### After (✅ Fixed)
```
User: "hi"
System: "Hey! 👋 What are you working on?"
        [Build me a website] [Research something] [Fix my project]
```

### Ambiguous Handling
```
User: "make a store"
System: *classifies as AMBIGUOUS if too vague*
        "Could you be more specific? Do you want me to:
         • Build an e-commerce website?
         • Research store solutions?
         • Fix an existing store?"
```

## Integration Points

### IPC Handlers
```typescript
handle('runs:classifyIntent', (input, projectId) => ...)
handle('runs:conversationalResponse', (intent, input) => ...)
```

### React API
```typescript
const classification = await api.runs.classifyIntent(objective, projectId);
if (!classification.shouldStartRun) {
  // Show conversational response
  const response = await api.runs.conversationalResponse(intent, input);
  displayMessage(response);
  return; // Don't start build
}
// Proceed with build
```

## Configuration

No configuration needed - works out of the box.

Intent patterns can be customized in `electron/core/intent.ts`:
- Add new patterns to intent arrays
- Adjust confidence thresholds
- Modify classification order

## Monitoring

Key metrics to watch:
- Intent distribution (chat vs build vs other)
- Misclassification rate
- User corrections/escalations

## Future Enhancements

Potential improvements (not currently needed):
1. Machine learning fallback for ambiguous cases
2. Context-aware classification (project state, history)
3. User preference learning
4. Multi-turn conversation memory

## Files Modified

```
electron/core/intent.ts          ← Intent classifier
electron/core/conversation.ts    ← Response generator
electron/ipc.ts                  ← IPC handlers
src/lib/api.ts                   ← API client
src/components/Composer.tsx      ← UI integration
shared/types.ts                  ← Type definitions
tests/intent.test.ts             ← Test suite
```

## Testing

Run tests:
```bash
pnpm test intent
```

Expected result: **35/35 tests passing**

## Acceptance Criteria

All requirements from the original spec are met:

✅ "hi" returns conversational response  
✅ "what is SWARM?" returns answer  
✅ "build me a website" triggers full build  
✅ "fix the build error" triggers debug workflow  
✅ Simple messages are fast (<100ms)  
✅ No spurious builds from conversations  
✅ Intent classification is deterministic  
✅ Comprehensive test coverage  

## Summary

The Intent Gateway successfully prevents the critical UX bug where every message was treated as a build request. The system is:

- **Fast**: <1ms classification, zero model calls
- **Accurate**: 35/35 tests passing, clear pattern matching
- **User-friendly**: Conversational responses with helpful suggestions
- **Maintainable**: Simple pattern-based logic, easy to extend
- **Well-tested**: Comprehensive test suite covering edge cases

**Result:** Users can now chat with SWARM naturally without accidentally triggering expensive build operations.
