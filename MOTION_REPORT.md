# SWARM motion polish

Implemented a shared CSS motion language in `src/motion.css`: instant, fast, normal, slow, spring, emphasis, ambient and bounded stagger tokens. Retained-surface transitions use `src/lib/motion.ts`, with cancellation on unmount, system reduced-motion changes and the application's animation preference. No execution or routing waits for animation.

Coverage includes startup/navigation, buttons and keyboard focus, switches/segments, composer focus/send feedback, chat arrivals and copy confirmation, streaming indicators, contextual agent activity, agent selection, graph message signals, task arrivals/completion, progress, graph success/failure, activity arrivals, dialogs/menus, project/model states, theme colors and preview availability. Graph positioning and progress use transforms. Historical chat messages remain still when reopening a saved conversation. Hidden persistent views pause CSS animations.

Also fixed theme listener cleanup, production CSS time-unit parsing, and immediate graph-message signal detection. Existing architecture and backend routing remain intact.

## Validation — 3 October 2026

- TypeScript typecheck and production renderer/main builds passed.
- Unit suite: 187 passed, 2 skipped by the existing suite.
- `scripts/motion-qa.mjs`: 18 assertions passed in the production Electron renderer. Covers idle settling, navigation, Models/Settings, four-agent graph, message signal, agent switching, failure/recovery/completion, task list, interactive local web preview, reduced motion and repeated navigation. No renderer exceptions. Uses isolated persisted fixtures and controlled IPC state events; this is not a new live-provider build acceptance run.
- `scripts/e2e-modes-ui.mjs`: 17 checks passed, including streaming, fallback, all-model failure, retry, attachments, history, edit/regenerate, restart and Chat/Build switching. Uses the real UI/IPC/router with a controlled local HTTP model provider.
- `scripts/factory-ui-qa.mjs motion`: no overflow at 1280×720, 1440×900, 1920×1080 or 2560×1440; no renderer exceptions. Most warmed mode switches measured approximately 28–35 ms. Full Electron startup was approximately 816 ms (includes process/bootstrap; visual tokens are 80–520 ms). Point-in-time CPU measurements were 0%; these are not a sustained performance benchmark or proof against all memory leaks.
- Actual CLI input/output and restart, and Windows executable capture/restart passed in `scripts/overhaul-preview-qa.mjs`.
- Android: initial launch/capture worked but tap verification failed. Added an interactive-screen readiness check to the test. The emulator then disconnected; after restarting the installed Pixel_7 emulator, Android reported no launchable fixture activity. Android interaction/restart remains unverified. The emulator started for this verification was shut down afterward. No Android runtime implementation was changed.

## Latest evidence

- `.swarm-test/motion-1791019422452/report.json` and screenshots
- `.swarm-test/two-modes-ui-1791019402544/report.json`
- `.swarm-test/factory-motion-1791019224537/report.json`
- `.swarm-test/overhaul-preview-1791019316716/report.json`

Run `node scripts/motion-qa.mjs` to repeat the isolated motion acceptance checks. The updated assets are in `dist`; launch with `npm start`. No installer/package rebuild was performed.
