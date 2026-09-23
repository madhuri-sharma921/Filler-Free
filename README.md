# Filler-Free: Live

A real-time AI speech coach built on Agora's Conversational AI Engine.
You explain something out loud — a bug you fixed, a project, an interview
answer — and the AI interrupts you immediately, in under 8 words, the
moment you say "um," repeat yourself, or ramble. Then it goes quiet again
the instant you're being clear. Silence is the reward for a clean
explanation.

This started as a solo-coaching app and grew into "Filler-Free: Live" for
a hackathon: MCP tool-calling, group coaching sessions with a mentor and
audience, and a CLI companion on top of the original core.

## The idea, in one paragraph

Instead of building a custom speech pipeline — audio capture, voice
activity detection, ASR, an interruption policy, TTS — I configured
Agora's Conversational AI Engine to do the barge-in for me. The actual
product is a system prompt that tells the agent exactly when and how
tersely to interrupt. Noise suppression, echo cancellation,
turn-detection, streaming transcription, low-latency TTS — all of that
is Agora's engine, configured rather than reimplemented. The bet is that
the hard, valuable part of this is knowing what an AI coach should say
and when, not re-deriving a real-time audio stack a mature platform
already solved.

## Architecture

- `fillerfree/domain` — pure Kotlin, zero Android dependencies, fully
  unit-testable: filler/repetition detection (`AnalyzeTranscriptUseCase`),
  session stats, summary generation (`BuildSessionSummaryUseCase`)
- `fillerfree/data` — `TranscriptAnalyticsRepositoryImpl`, in-memory,
  implements the domain repository contract
- `fillerfree/config` — `CoachAgentPromptBuilder`: this is the actual
  product logic. It builds the system prompt that controls when and how
  the agent interrupts. If someone asks what the AI is actually doing,
  this file is the honest answer — I tune it here before touching any UI.
- `fillerfree/presentation` — MVI-style `FillerFreeViewModel` (StateFlow)
  + three Compose screens (topic select → live session → summary), with
  a deliberately blunt dark "scoreboard" visual theme instead of a soft
  chat UI
- `fillerfree/live` — the group-session (Interactive Live Streaming) layer,
  a separate RTC engine and UI stack from solo coaching, described below
- `server/app/mcp` — the MCP tool server the coach agent calls
  mid-conversation
- `server/app/live` — backend for group rooms: role management, tokens,
  the second coach agent that joins a room
- `server/cli` — the headless CLI companion

The transport layer — Agora RTC/RTM session, token bootstrap, agent
invite/leave, barge-in detection — is the original Agora quickstart code,
reused as-is. Filler-Free adds an analytics layer on top of the
transcript stream it already exposes, plus everything below.

## What changed vs. the stock Agora quickstart (core diff, three files)

1. **`MainActivity.kt`** — launches `FillerFreeScreen`/`FillerFreeViewModel`
   instead of `ConversationScreen`/`ConversationViewModel`. The mic
   permission flow is untouched from the original — "Start talking" is
   gated behind the same `RECORD_AUDIO` check before `startSession()` runs.
2. **`data/ConversationAgoraApi.kt`** — `inviteAgent(...)` gained an
   optional `systemPrompt: String? = null` parameter, forwarded as
   `system_prompt` in the JSON request body.
3. **`data/ConversationRepository.kt`** — same optional parameter threaded
   through from the ViewModel to the API layer.

That `system_prompt` field was already fully supported server-side
(`server/app/schemas.py` → `JoinRequest.system_prompt` → `agora_client.py`
→ `.with_llm(system_messages=[...])`) — the backend just had no Android
caller sending it yet. I didn't touch any server code for that part.

Everything else in the original quickstart (RTC session manager, turn
manager, barge-in detector, audio pipeline, theme, Python server) is
untouched.

## Setup — you'll need your own Agora account

This repo intentionally doesn't include Agora credentials or a running
backend, since those are tied to my own Agora account, not yours.

```bash
# 1. Install the Agora CLI and log in with your own Agora account
curl -fsSL https://dl.agora.io/cli/install.sh | sh
agora login

# 2. Bind this folder to an Agora project (writes local.properties)
agora quickstart env write . --template android --project <your-project-name>

# 3. Sanity-check the setup
agora project doctor --deep
```

Then run the Python backend (needed for token bootstrap + agent invite):

```bash
cd server
pip install -r requirements-dev.txt
cp -n .env.example .env.local   # fill in AGORA_APP_ID / AGORA_APP_CERTIFICATE
./run.sh
```

If you're testing on a physical device, you'll need a public HTTPS
tunnel — see `docs/local-tunnels.md`. Once you have a tunnel URL:

```bash
./server/configure-android.sh https://your-tunnel-url
```

Then open the root folder in Android Studio, let Gradle sync, build, and
run.

To turn on MCP tool-calling (the coach checking its own stats
mid-session), also set this in `server/.env.local` and restart the
backend:

```
MCP_TOOL_SERVER_PUBLIC_URL=https://your-tunnel-url
```

Agora's own servers call that URL directly, so it has to be the public
tunnel address, not `127.0.0.1`. Leave it blank to run without
tool-calling — nothing else changes.

## Demo script (45–60 seconds, solo mode)

1. Open the app, pick "Explain a bug you fixed"
2. Tap "Start talking", grant mic permission
3. Explain something real, but ramble a little on purpose — watch the
   agent cut in live 2–3 times (the FILLERS/CUT-INS counters tick up,
   the screen briefly flashes amber on each interruption)
4. Tap "End session" — the closing card shows your top habit
5. Tap "Try again, tighter" — same explanation, cleaner, fewer
   interruptions

No narration needed. The live interruption is the whole pitch.

## Known limitations, worth saying up front

- Client-side filler/repetition detection (`AnalyzeTranscriptUseCase`) is
  a word-list + overlap-ratio heuristic, not real NLP — good enough for a
  demo, not production-grade.
- The interruption *behavior itself* comes from the LLM following the
  system prompt, combined with Agora's own turn-detection/barge-in
  pipeline. The on-screen counters are analytics layered on top of that
  stream — they don't trigger the interruption themselves.
- No persistence across backend restarts for group rooms (by design, for
  now) — room state lives in memory and resets when the server restarts.

---

## Filler-Free: Live — MCP tool-calling, Interactive Live Streaming, CLI companion

Three additions on top of everything above, without touching the
original solo-coaching flow's core logic. Each is optional: leave
`MCP_TOOL_SERVER_PUBLIC_URL` blank, never open the Live tab, never run
the CLI, and the app behaves exactly as described above.

### 1. MCP tool-calling — the coach checks real numbers instead of guessing

`server/app/mcp/tool_server.py` runs a real MCP server (Streamable HTTP,
mounted at `<backend_url>/mcp`) exposing three tools to the coach agent:

- `get_live_stats` — the running filler/repetition/interruption count for
  this session, so the agent can say "that's your sixth um" instead of
  guessing.
- `queue_practice_topic` — lets the agent queue a targeted redo, surfaced
  as a "Try again: <topic>" suggestion on the session summary screen.
- `get_session_history` — a short server-side history so the agent can
  check whether a habit is actually recurring before calling it out as
  one.

`agora_client.py` passes `llm.mcp_servers` and
`advanced_features.enable_tools` to Agora's Conversational AI Engine —
this is a real, documented Agora feature, not something I bolted on
separately. `CoachAgentPromptBuilder`'s `GENERAL` role tells the agent
the tools exist and when to reach for them. `FillerFreeViewModel` mirrors
the same numbers the on-screen scoreboard uses into the server
(`/v1/coach-tools/live-stats`) so there's something real for the tools to
read, and checks for a queued suggestion when the session ends.

This needs a publicly reachable URL, since Agora's own servers call your
MCP endpoint directly, not your phone — see the setup section above for
`MCP_TOOL_SERVER_PUBLIC_URL`.

### 2. Interactive Live Streaming — a mentor co-host, a silent audience, and the coach in the room

`server/app/live/` adds a second, parallel channel type: a "room" with a
speaker (the person practicing), at most one mentor co-host, and any
number of silent audience members — full audio **and video**. Speaker
and mentor publish camera + mic; audience receives both but publishes
neither, enforced by Agora's `CLIENT_ROLE_AUDIENCE` at the protocol
level, not just hidden UI buttons. This is a genuinely different Agora
channel profile (`LIVE_BROADCASTING` with broadcaster/audience roles)
from the solo coaching channel (`COMMUNICATION`, no roles), so it runs on
a separate RTC engine instance on the Android side
(`fillerfree/live/data/LiveRoomSessionManager.kt`), not a mode flag on
the existing one.

A second coach agent joins the room's own channel (`live/routes.py`'s
`create_room` calls the same `agora.join_agent` the solo flow uses, with
a group-aware system prompt) so the mentor and audience actually hear it
correcting the speaker's filler words live, not just the speaker alone.
It's told explicitly to defer to a human mentor's voice and stay terse in
a group setting rather than reusing the solo prompt verbatim. If the
agent invite fails, the room still opens — `coach_agent_active` in the
room state reports honestly whether it's actually there, and the UI
shows a live "Coach is live" / "Coach unavailable" indicator instead of
pretending. Leaving as speaker stops that room's agent so it doesn't
keep running in an empty channel, and going live again from the same
device automatically closes out any room you'd left open from before,
rather than leaving duplicates sitting in the browse list.

Reach it from the "Go Live" button on the topic-select or summary
screen. Host a room as the speaker, or browse to join an in-progress one
as a mentor (co-host, can speak and be seen) or audience (silent,
watch-only). The speaker can promote or demote participants between
mentor and audience from inside the room, and toggle or flip their own
camera.

### 3. CLI companion — headless, for demos and testing

```bash
cd server
pip install -e .          # installs the `filler-free-cli` command
filler-free-cli health --server https://your-tunnel-url
filler-free-cli start --server https://your-tunnel-url --topic explain_bug --watch 60
filler-free-cli rooms --server https://your-tunnel-url
filler-free-cli watch-room <room_id> --server https://your-tunnel-url
```

`start` drives the exact same bootstrap → join → leave lifecycle the
Android app uses, and shows a live-updating table of the actual
filler/repetition/interruption counts `get_live_stats` hands the agent,
plus a callout the moment the agent calls `queue_practice_topic` — a
direct, visible trace of the MCP tool-calling loop in a terminal, not a
simulated one. I use it as a quick smoke test that the backend, Agora
credentials, and MCP wiring actually work together before touching a
phone, and it also works as an on-stage demo of just the tool-calling
loop. It doesn't publish microphone audio — there's no RTC audio path in
a terminal — so run it alongside the real Android app on the same
channel if you want the numbers to move. `rooms` and `watch-room` are a
read-only view into a room's state for the same reason.

### Not included: cloud recording

Agora's Cloud Recording is a separate REST service that needs its own
cloud storage bucket (S3/OSS/Azure) configured on your own Agora project
console — that account-specific setup isn't something I can do from
inside this codebase, so I left it out on purpose rather than ship it
half-working. `AgoraClient` and the session lifecycle in
`routes.py`/`live/routes.py` are structured so adding a
`start_recording`/`stop_recording` pair keyed by `channel_name` later is
a localized change, not a redesign.

### What I've actually verified vs. what's still unconfirmed

- MCP tools, ILS room state, and the group-session coach-agent invite are
  real and tested end-to-end against a running backend — tool calls,
  room create/join/promote, agent join/leave for a room, and the stats
  bridge round-trip have all been verified with live HTTP requests,
  including confirming the real Agora ConvoAI `join` endpoint actually
  gets called.
- Video (camera capture, preview, remote rendering, camera toggle/flip)
  and audio for group sessions have been confirmed working on two
  physical Android devices — a live group session with the coach agent
  audible has actually run, not just built.
- Selective Attention Locking is a real Agora engine capability, but I
  haven't confirmed it's actually enabled in `agora_client.py` — worth
  checking before claiming it as a live feature.
- No local Android compiler was available while building the ILS/video
  layer, so a few of these were iterated through real on-device build
  errors rather than caught ahead of time. If something new breaks, the
  exact error/logcat output is the fastest way to fix it.
