# Filler-Free: Live

A real-time AI speech coach for Android. An Agora Conversational AI agent
listens while you speak, interrupts filler words and rambling, and can coach
a live group session with video.

## What you'll build

- Android app (Kotlin, Jetpack Compose)
- FastAPI backend that starts and manages the Agora Conversational AI agent
- Live group coaching: a "Go Live" session with speaker and mentor video feeds,
  built on Agora Interactive Live Streaming

## Prerequisites

- An Agora account with an App ID and App Certificate
- The AI provider keys listed in [setup.md](setup.md)
- Android Studio
- Python 3 (see [backend-runbook.md](backend-runbook.md) for the exact version)
- A real Android device (a second device is needed to test group sessions)

## Setup

1. Backend: follow [backend-runbook.md](backend-runbook.md).
2. Android app and credentials: follow [setup.md](setup.md).
3. If your phone can't reach a backend on your computer, see
   [local-tunnels.md](local-tunnels.md).
4. Start the backend, then run the app, then start a coaching session.

## How it works

1. The app asks the backend for a session. The backend generates the Agora
   token and starts the Conversational AI agent in the channel.
2. The agent listens to your speech in real time and speaks up when it
   detects filler words or rambling.
3. In group mode, one user goes live and others join the same session as
   audience, so a mentor can watch and coach.

More detail is in [architecture.md](architecture.md).

## Troubleshooting

See [troubleshooting.md](troubleshooting.md).
