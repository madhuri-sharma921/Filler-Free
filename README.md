# Filler-Free — Android + Agora Conversational AI

This is the real `agent-quickstart-android` project (cloned from
AgoraIO-Conversational-AI/agent-quickstart-android), with the "Filler-Free"
feature merged into it. The original quickstart's own README is preserved at
`docs/original-quickstart-readme.md`.

> **Presenting to judges?** `Filler-Free-Live-Project-Brief.pdf` (repo root)
> is a shorter, standalone document built for exactly that: the pitch,
> architecture, an honest verified-vs-not status table, and direct answers
> to the questions most likely to come up in Q&A. This README is the full
> technical reference underneath it — read the PDF first, come here for
> implementation detail.

**The idea:** a live speech coach. You explain something out loud — a bug
you fixed, a project, an interview answer. The AI agent interrupts
immediately (under 8 words) the moment you ramble, repeat filler words, or
go vague — then goes quiet again the moment you're being clear. Silence is
the reward for a clean explanation.

**For judges — what's technically deep here, in 30 seconds:**
1. The product's entire behavior is a system prompt, not a custom ML
   pipeline (`fillerfree/config/CoachAgentPromptBuilder.kt`) — barge-in,
   turn-detection, STT/TTS all come from Agora's Conversational AI Engine,
   configured rather than built from scratch. See "Architecture" below.
2. **MCP tool-calling**: the agent isn't just talking — it can call real
   tools mid-conversation to check its own state (`get_live_stats`) and act
   on it (`queue_practice_topic`), via Agora's `mcp_servers` config on a
   real Streamable-HTTP MCP server (`server/app/mcp/tool_server.py`).
3. **A second AI agent joins a group room** it's never been in before, with
   a prompt rewritten for a group setting that knows to defer to a human
   co-host's voice (`server/app/live/routes.py`) — the same underlying
   coaching behavior, correctly reconfigured for a different social
   context, not copy-pasted.
4. **Full audio+video Interactive Live Streaming** with real
   broadcaster/audience role enforcement at the Agora protocol level (not
   just UI-hidden buttons) — confirmed working with two live devices,
   video included.
5. A **headless CLI** exercises the same tool-calling loop with a
   live-updating terminal view of the actual MCP state, for a fast
   correctness check or an on-stage demo that doesn't depend on phone
   hardware at all.

## Architecture

- `fillerfree/domain` — pure Kotlin, zero Android dependencies, fully
  unit-testable: filler/repetition detection (`AnalyzeTranscriptUseCase`),
  session stats, summary generation (`BuildSessionSummaryUseCase`)
- `fillerfree/data` — `TranscriptAnalyticsRepositoryImpl`, in-memory,
  implements the domain repository contract
- `fillerfree/config` — `CoachAgentPromptBuilder`: the actual product logic.
  This builds the system prompt that controls when and how the agent
  interrupts. If you're asked "what is the AI actually doing," this file is
  the honest answer — tune it here before touching any UI.
- `fillerfree/presentation` — MVI-style `FillerFreeViewModel` (StateFlow) +
  three Compose screens (topic select → live session → summary), with a
  deliberately blunt dark "scoreboard" visual theme instead of a soft chat UI

The transport layer — Agora RTC/RTM session, token bootstrap, agent
invite/leave, barge-in detection — is the original quickstart code, reused
as-is. Filler-Free only adds a thin analytics layer on top of the transcript
stream it already exposes.

## What was changed vs. the stock quickstart (exact diff, three files)

1. **`MainActivity.kt`** — launches `FillerFreeScreen`/`FillerFreeViewModel`
   instead of `ConversationScreen`/`ConversationViewModel`. The mic
   permission request flow is preserved exactly as the original — Filler-Free's
   "Start talking" button is gated behind the same `RECORD_AUDIO` permission
   check before `startSession()` runs.
2. **`data/ConversationAgoraApi.kt`** — `inviteAgent(...)` gained an optional
   `systemPrompt: String? = null` parameter, forwarded as `system_prompt` in
   the JSON request body.
3. **`data/ConversationRepository.kt`** — same optional parameter threaded
   through from the ViewModel to the API layer.

That `system_prompt` field was **already fully supported server-side**
(`server/app/schemas.py` → `JoinRequest.system_prompt` → `agora_client.py`
→ `.with_llm(system_messages=[...])`) — the Python backend just had no
Android caller sending it yet. No server code was changed.

Everything else in the original quickstart (RTC session manager, turn
manager, barge-in detector, audio pipeline, theme, Python server) is
untouched.

## Setup — needs your own Agora account, can't be pre-generated

This zip intentionally does **not** include Agora credentials or a running
backend, since those are tied to your own Agora account.

```bash
# 1. Install the Agora CLI and log in with YOUR Agora account
curl -fsSL https://dl.agora.io/cli/install.sh | sh
agora login

# 2. Bind this existing folder to an Agora project (writes local.properties)
agora quickstart env write . --template android --project <your-project-name>

# 3. Sanity-check the setup
agora project doctor --deep
```

Then run the Python backend (needed for token bootstrap + agent invite):
```bash
cd server
pip install -r requirements.txt
./run.sh
```
See `docs/local-tunnels.md` if you're testing on a physical device and need
to tunnel the backend.

Then open the root folder in Android Studio, let Gradle sync, build, and run.

## Demo script (45–60 seconds)

1. Open the app, pick "Explain a bug you fixed"
2. Tap "Start talking", grant mic permission
3. Explain something real, but ramble a little on purpose — watch the agent
   cut in live 2–3 times (FILLERS/CUT-INS counters tick up, screen briefly
   flashes amber on each interruption)
4. Tap "End session" → closing card shows your top habit
5. Tap "Try again, tighter" → same explanation, cleaner, fewer interruptions

No narration needed — the live interruption is the whole pitch.

## Known limitations (worth being upfront about)

- Client-side filler/repetition detection (`AnalyzeTranscriptUseCase`) is a
  word-list + overlap-ratio heuristic, not real NLP — good enough for a
  hackathon demo, not production-grade.
- The interruption *behavior itself* is driven by the LLM following the
  system prompt, combined with Agora's own turn-detection/barge-in
  pipeline — the on-screen counters are analytics layered on top of that
  stream, not what triggers the interruption.
- No persistence across sessions (by design, for MVP simplicity) — every
  session starts fresh.

---

## "Filler-Free: Live" — MCP tool-calling, Interactive Live Streaming, CLI companion

Three genuinely new capabilities, added on top of everything above without
touching the original solo-coaching flow's core logic. Each is additive: if
you never configure it (leave `MCP_TOOL_SERVER_PUBLIC_URL` blank, never open
the Live tab, never run the CLI), the app behaves exactly as described above.

### 1. MCP tool-calling — the coach can check real numbers instead of guessing

`server/app/mcp/tool_server.py` runs a real MCP server (Streamable HTTP,
mounted at `<backend_url>/mcp`) exposing three tools to the coach agent:

- `get_live_stats` — the running filler/repetition/interruption count for
  this session, so the agent can say "that's your sixth um" instead of
  guessing.
- `queue_practice_topic` — lets the agent queue a targeted redo, surfaced as
  a "Try again: <topic>" suggestion on the session summary screen.
- `get_session_history` — a short server-side history so the agent can check
  whether a habit is actually recurring before calling it out as one.

Wiring: `agora_client.py` passes `llm.mcp_servers` + `advanced_features.enable_tools`
to Agora's Conversational AI Engine (this is a real, documented Agora
feature — see `docs.agora.io/en/ai/build/mcp-tools`); `CoachAgentPromptBuilder`'s
`GENERAL` role tells the agent the tools exist and when to reach for them;
`FillerFreeViewModel` mirrors the same numbers the on-screen scoreboard uses
into the server (`/v1/coach-tools/live-stats`) so there's something real for
the tools to read, and checks for a queued suggestion when the session ends.

**This needs a publicly reachable URL** — Agora's own servers call your MCP
endpoint, not your phone — so set `MCP_TOOL_SERVER_PUBLIC_URL` in
`server/.env.local` to the same tunnel URL you already use for the backend
(see `docs/local-tunnels.md`). Leave it blank to disable tool-calling
entirely; nothing else changes.

### 2. Interactive Live Streaming — a mentor co-host, a silent audience, and the coach in the room

`server/app/live/` adds a second, parallel channel type: a "room" with a
speaker (the person practicing), at most one mentor co-host, and any number
of silent audience members — with full audio **and video**: speaker and
mentor publish camera + mic (`fillerfree/live/data/LiveRoomSessionManager.kt`
calls `enableVideo()`/`startPreview()`, real Agora video canvases render in
`LiveRoomScreen.kt`'s video grid); audience receives both but publishes
neither, enforced by Agora's `CLIENT_ROLE_AUDIENCE` itself, not just the UI.
This is a genuinely different Agora channel profile (`LIVE_BROADCASTING`
with broadcaster/audience roles) from the solo coaching channel
(`COMMUNICATION`, no roles), so it's a separate RTC engine instance on the
Android side, not a mode flag on the existing one.

**A second coach agent joins the room's own channel** (`live/routes.py`
`create_room` calls the same `agora.join_agent` the solo flow uses, with a
group-aware system prompt — see `_group_coach_system_prompt`) so the mentor
and audience actually hear it correcting the speaker's filler words live,
not just the speaker alone. It's told explicitly to defer to a human
mentor's voice and stay terse in a group setting rather than reusing the
solo prompt verbatim. If the agent invite fails (no Agora credentials
configured, network issue), the room still opens — `coach_agent_active`
in the room state reports honestly whether it's actually there, and the
UI shows a live "Coach is live" / "Coach unavailable" indicator rather than
silently pretending. Leaving as speaker stops that room's agent so it
doesn't keep running in an empty channel.

Reach it from the "Go Live" button on the topic-select or summary screen.
Host a room as the speaker, or use "Browse" to join an in-progress one as a
mentor (co-host, can speak and be seen) or audience (silent, watch-only).
The speaker can promote/demote participants between mentor and audience
from inside the room, and toggle/flip their own camera.

### 3. CLI companion — headless, for demos and testing

```bash
cd server
pip install -e .          # installs the `filler-free-cli` command
filler-free-cli health --server https://your-tunnel-url
filler-free-cli start --server https://your-tunnel-url --topic explain_bug --watch 60
filler-free-cli rooms --server https://your-tunnel-url
filler-free-cli watch-room <room_id> --server https://your-tunnel-url
```

`start` drives the exact same bootstrap → join → leave lifecycle the Android
app uses, and shows a live-updating table of the actual filler/repetition/
interruption counts `get_live_stats` hands the agent, plus a callout the
moment the agent calls `queue_practice_topic` — a direct, visible trace of
the MCP tool-calling loop in a terminal, not a simulated one. Useful as a
quick smoke test that your backend + Agora credentials + MCP wiring
actually work together before touching a phone, or as an on-stage demo of
just the tool-calling loop. It intentionally does **not** publish
microphone audio — there's no RTC audio path in a terminal — so run it
alongside the real Android app on the same channel if you want the numbers
to move. `rooms`/`watch-room` are a read-only view into an ILS room's state
for the same reason.

### Not included: cloud recording

Agora's Cloud Recording is a separate REST service that needs its own cloud
storage bucket (S3/OSS/Azure) configured on your Agora project console — that
account-specific setup can't be done from inside this codebase, so it's
intentionally left out of this pass. `AgoraClient` and the session lifecycle
in `routes.py`/`live/routes.py` are structured so adding a
`start_recording`/`stop_recording` pair keyed by `channel_name` later is a
localized change, not a redesign.

### What's genuinely new vs. what's a thin wrapper

- MCP tools, ILS room state, and the group-session coach-agent invite are
  **real, working, tested** end-to-end against a running backend (tool
  calls, room create/join/promote, agent join/leave for a room, and the
  stats bridge round-trip all verified with live HTTP requests during
  development — including confirming the real Agora ConvoAI `join` endpoint
  gets called, not a mock).
- Video (camera capture, preview, remote rendering, camera toggle/flip) and
  the Android ILS UI (`fillerfree/live/`) have been **built, iterated
  through several real compile errors caught on-device, and confirmed
  working on two physical Android devices** — a live group video session
  with the coach agent audible has actually run. Treat it as tested, not
  merely written.
- Everything Android-side was still authored without a local compiler
  available in this environment (no Android SDK/Gradle here); each fix in
  this project's history came from an actual Android Studio error message,
  not a guess caught in advance. If you hit a new one, paste the exact
  error/logcat output and it can be fixed the same way the earlier ones
  were.