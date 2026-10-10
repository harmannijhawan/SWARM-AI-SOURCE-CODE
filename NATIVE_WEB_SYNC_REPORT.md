# SWARM 1.0.4 — shared workspace release

The desktop and Android default workspace now loads https://www.swarmgpt.online/app. The same deployed UI, responsive styling, conversation history, Free/Pro/Max entitlements, model profiles, checkout, and server routing are therefore used by all three. Future web updates appear after reload without maintaining three separate implementations.

Windows local projects, terminals, automation, phone pairing and permissions remain in Workspace > Local desktop tools and the tray menu. Android's Connect PC and Native workspace buttons retain the existing companion and native account clients. These native workspaces are separate from the shared web renderer and require their existing device sign-in; this release does not make cloud JavaScript a local automation bridge.

Hosted rendering requires internet connectivity. Desktop web content runs without preload, Node integration or a native bridge, with context isolation and sandboxing. Native IPC rejects non-file frames. Android disables file/content access and mixed content, preserves cookies, uses a native file chooser, and opens downloads/external links in the system browser. WebView-specific OAuth/voice support and live checkout have not been device-verified; use email sign-in or the existing native/system-browser sign-in flow where needed. Browser permission and Android platform differences still apply.

Android launcher uses the user's supplied white logo. Versions: desktop 1.0.4; Android versionCode 4, versionName 1.0.4. APK is the existing debug-signed development build, not a Play Store production release. Windows Authenticode trust is not claimed.

Checks: desktop build and TypeScript check passed; Android assembleDebug passed; 55 chat, runtime-preview and remote-auth tests passed; desktop live web smoke loaded the production /app and confirmed window.swarm undefined; packaged native startup/IPC/sign-in-origin smoke passed. Real provider calls, paid checkout, Android installation and an authenticated full end-to-end session were not executed.

Download binaries are published as GitHub release assets, with SHA-256 hashes in the release manifest. Website download links select this release instead of stale deployment URLs. Native source snapshot in the website repository is synchronized with the build source. Credentials are not included.
