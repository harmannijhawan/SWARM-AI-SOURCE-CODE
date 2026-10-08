# SWARM AI

**One mission. Many minds.**

SWARM AI is a multi-agent AI system where specialized agents collaborate to plan, research, build, test, and review complex tasks. This repository preserves the existing Electron application and its Android companion; it is not a replacement or demo implementation.

## Features

- Multi-agent orchestration, live run chat, approvals, and run history.
- Provider/model discovery, routing, health checks, and local Ollama support.
- Project files, terminal, browser, research, previews, and workspace tools.
- Remote companion connectivity and Android companion application.
- First-run provider setup and configurable appearance, behavior, and security settings.

Provider availability and model access depend on the provider, network, and credentials configured by the user.

## Architecture

- `src/`: React, TypeScript, and Vite renderer. The existing UI and routes are retained.
- `electron/`: Electron main process, IPC, agents, providers, persistence, and native integrations.
- `shared/`: Types and shared settings contracts.
- `resources/`, `public/`: Existing application icons and visual assets.
- `app/`, root Gradle files, and `gradle/`: Android companion source and build configuration.
- `scripts/`, `tests/`: Build and QA scripts, plus automated tests.

The renderer uses a restricted preload/IPC bridge to access the Electron backend. Application data, including conversations and settings, is stored locally in Electron's user-data directory; no user database is part of this source repository.

## Requirements

- Windows 10/11 for the supported desktop build.
- Node.js compatible with the installed Electron/Vite toolchain.
- pnpm (the repository includes `pnpm-lock.yaml` and a pnpm workspace file).
- For Android development: a compatible JDK, Android SDK, and Android Studio or Gradle command-line tools.

## Installation

From the repository root:

```powershell
corepack enable
pnpm install --frozen-lockfile
```

If Corepack is unavailable, install a pnpm version compatible with the lockfile, then run `pnpm install --frozen-lockfile`. Do not commit local environment files, credentials, generated databases, or build output.

## Environment variables

`.env.example` lists the provider credential names recognized by the existing application. The Electron app does not automatically load `.env` files; set an environment variable in the launching shell if using environment-based credentials. Alternatively, enter a key in first-run setup or **Settings > Providers**.

No database URL is required: SWARM uses a local SQLite database. Provider credentials are optional and depend on the services you choose; Ollama can provide local models without a cloud API key.

## Development

```powershell
pnpm dev
```

This builds the Electron main process and renderer, then launches the desktop app. Useful checks:

```powershell
pnpm typecheck
pnpm test
pnpm build
```

## Web application

The current product is an Electron desktop application, not a standalone web server. The Vite renderer build is part of the desktop app and relies on Electron IPC for backend functionality. `pnpm build:renderer` builds that renderer; it does not produce a deployable, feature-complete web application by itself.

## Windows desktop application

For development:

```powershell
pnpm desktop:dev
```

To build the Windows NSIS installer:

```powershell
pnpm desktop:build
```

The installer is written to `release/` as `SWARM-AI-Setup.exe`. The build needs network access to retrieve any uncached packaging dependencies. Only distribute an installer after testing it on a clean Windows machine.

## API keys / BYOK

Users provide their own provider credentials where required. On first launch, SWARM offers optional provider-key fields; keys can also be added or removed later in **Settings > Providers**. The renderer can submit a key for saving but cannot read stored key values back; it receives only a masked hint. On Windows, Electron `safeStorage` uses OS-backed encryption (DPAPI). SWARM refuses to save a new key if secure storage is unavailable and removes legacy plaintext-formatted key records rather than using them.

Keys must not be committed, logged, included in screenshots, or shared in issue reports. Environment-provided keys remain in the launching process environment and are not stored by SWARM.

## Security notes

- Keep `.env`, local properties, signing keys, database files, and personal data out of version control.
- The included `.gitignore` excludes common secrets, local databases, caches, build output, and IDE files. Review staged files before every push.
- Provider keys are sent to the provider that handles a request. Review that provider's terms and privacy policy.
- Local DB encryption is not claimed; protect the operating-system account and device.
- This repository has no selected open-source license yet (`package.json` says `UNLICENSED`). The owner must choose and add an appropriate license before authorizing redistribution or outside contributions.

## Contributing

Preserve the existing SWARM UI and behavior. Please run `pnpm typecheck`, `pnpm test`, and `pnpm build` for changes affecting the application, and do not include credentials, personal data, or generated output in pull requests.

## License

No license file is included. The project is currently marked `UNLICENSED`; do not assume permission to redistribute or reuse the source until the repository owner publishes a license.
