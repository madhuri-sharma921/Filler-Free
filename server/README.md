# Python conversation server

This FastAPI service keeps the Agora App Certificate and Conversational AI REST calls off the Android device. It exposes bootstrap, join, interrupt, leave, refresh, and health endpoints on `http://127.0.0.1:8000` by default. The tunnel provider terminates public HTTPS.

It also hosts the MCP tool server the coach agent calls mid-conversation (`/mcp`), the Interactive Live Streaming room endpoints (`/v1/live/...`), and the small stats/history bridge those tools read from (`/v1/coach-tools/...`) — see the root `README.md`'s "Filler-Free: Live" section for what each of those actually does.

## Setup

```bash
python3 -m venv server/.venv
source server/.venv/bin/activate
pip install -r server/requirements-dev.txt
cp -n server/.env.example server/.env.local
```

Use the Agora CLI to seed the backend credentials. The App Certificate remains on this server and is used to generate the mobile user's RTC/RTM token and the agent's Agora credentials:

```bash
agora project env write server/.env.local
```

Run the loopback HTTP server:

```bash
./server/run.sh
```

In another terminal, expose it through a public HTTPS tunnel:

```bash
./server/tunnel.sh --provider ngrok
```

Choose `cloudflare`, `ngrok`, `tailscale`, or `localtunnel` with the `--provider` flag. You can also run any provider directly; see [`docs/local-tunnels.md`](../docs/local-tunnels.md).

Write the tunnel URL into Android configuration with:

```bash
./server/configure-android.sh https://your-public-host
```

Android connects only to the tunnel provider's publicly trusted HTTPS endpoint. The loopback HTTP server is not exposed directly to the device or local network.

**To enable MCP tool-calling**, also set `MCP_TOOL_SERVER_PUBLIC_URL` in `server/.env.local` to that same tunnel URL (e.g. `MCP_TOOL_SERVER_PUBLIC_URL=https://your-public-host`) and restart the server — Agora's own servers call this URL directly, so it has to be the public tunnel address, not `127.0.0.1`. Leave it blank to run without tool-calling.

See `docs/backend-runbook.md` for the API contract, deployment alternatives, and smoke checks.

## CLI companion

A headless client that drives this same backend, for demos and quick smoke-testing without a phone:

```bash
pip install -e .
filler-free-cli health --server https://your-public-host
filler-free-cli start --server https://your-public-host --topic explain_bug --watch 60
```

Run `filler-free-cli --help` for the full command list. See the root `README.md` for what each command does and why it doesn't publish real microphone audio.
