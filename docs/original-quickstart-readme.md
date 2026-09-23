# Agora Conversational AI Android Quickstart (original README)

This is the original README from AgoraIO-Conversational-AI/agent-quickstart-android,
preserved here for reference since the top-level README.md now documents the
Filler-Free feature built on top of this quickstart.

This repository is a template-style Android starter for building a Voice AI
app with Agora Conversational AI.

It gives you a single Kotlin + Jetpack Compose app that:
- joins an Agora RTC channel
- starts an Agora Conversational AI agent
- listens for transcript, agent state, and pipeline metrics over RTM
- lets the user talk, mute, interrupt, and end the session
- keeps the demo in one Android project so it is easy to understand and customize

## Quick Start

The recommended path is to let the Agora CLI clone the quickstart, bind an
Agora project, and write Android credentials to `local.properties`.

### 1. Install the Agora CLI and sign in
```
curl -fsSL https://dl.agora.io/cli/install.sh | sh
agora --help
agora login
```

### 2. Bind THIS existing folder to an Agora project
Since you already have this folder (it's not being freshly cloned), use:
```
agora quickstart env write . --template android --project <your-project>
```
This writes `.agora/project.json` and Agora credentials to root `local.properties`.

### 3. Build the app
```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

### 4. Run it
Open the project in Android Studio, or run it from the command line, then
launch it on a device or emulator. Tap **Start voice session** (now
"Start talking" in the Filler-Free UI), allow microphone permission, speak
to the agent, and watch transcripts appear in realtime.

If the agent does not join or transcripts do not appear, run:
```
agora project doctor --deep
```

## What To Read First (original quickstart's own guidance)
- `ConversationScreen.kt` — original stock UI (no longer launched by
  MainActivity, but still present in `ui/` for reference)
- `ConversationViewModel.kt` — original stock ViewModel (same note as above)
- `AgoraConversationSessionManager.kt` — the RTC session engine; Filler-Free
  reuses this unchanged
- `ConversationAgoraApi.kt` — backend calls; Filler-Free added an optional
  `systemPrompt` parameter to `inviteAgent(...)`, otherwise unchanged
- `AgoraLocalTokenFactory.kt` — demo-only local token generation

## Docs
- `docs/setup.md`: prerequisites, CLI setup, manual setup, required config, production security notes
- `docs/architecture.md`: app structure, code map, session lifecycle, state flow
- `docs/troubleshooting.md`: common setup, agent, RTM, metrics, and microphone issues
- `docs/agent-guidance.md`: Agora CLI skills and guidance for AI coding agents
- `docs/backend-runbook.md`, `docs/local-tunnels.md`, `docs/production-migration-plan-agora-cli-python-backend.md`: server-side docs

## Security Note (still applies)
This quickstart is intentionally convenient, not production-safe as-is.
Because `AGORA_APP_CERTIFICATE` is packaged into the app for local token
generation, anyone with the built APK can extract it and use your Agora
project. Before shipping publicly, move token generation and REST `join`,
`interrupt`, and `leave` calls to your backend (the included `server/`
folder already does this — make sure you deploy it rather than relying on
local token generation for anything beyond a hackathon demo).
