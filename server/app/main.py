from __future__ import annotations

import logging
import time
import uuid
from contextlib import AsyncExitStack, asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from .agora_client import AgoraClient
from .config import Settings
from .live.live_room_store import LiveRoomStore
from .live.routes import create_live_router
from .mcp.live_stats_store import LiveStatsStore
from .mcp.routes import create_mcp_bridge_router
from .mcp.session_history_store import SessionHistoryStore
from .mcp.tool_server import build_mcp_tool_server
from .routes import create_router
from .session_store import SessionStore


logger = logging.getLogger("uvicorn.error")


def create_app(
    settings: Settings | None = None,
    agora_client: AgoraClient | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_env()
    store = SessionStore(resolved_settings.session_ttl_seconds)
    agora = agora_client or AgoraClient(resolved_settings)

    live_stats_store = LiveStatsStore()
    session_history_store = SessionHistoryStore()
    live_room_store = LiveRoomStore()

    # The MCP tool server is its own mini-ASGI app (streamable-http
    # transport), mounted under /mcp. It has its own internal lifespan
    # (the streamable-http session manager) that MUST run for the /mcp
    # endpoint to work at all -- FastAPI does not start a sub-app's
    # lifespan automatically just because it's mounted, so the outer
    # lifespan below explicitly enters it via AsyncExitStack.
    mcp_tool_server = build_mcp_tool_server(live_stats_store, session_history_store)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        resolved_settings.validate()
        async with AsyncExitStack() as stack:
            await stack.enter_async_context(mcp_tool_server.session_manager.run())
            yield
            await agora.close()

    application = FastAPI(
        title="Agora Android Quickstart Server",
        version=resolved_settings.build_version,
        lifespan=lifespan,
    )
    application.state.settings = resolved_settings
    application.state.session_store = store
    application.state.live_stats_store = live_stats_store
    application.state.session_history_store = session_history_store
    application.state.live_room_store = live_room_store
    application.add_middleware(
        CORSMiddleware,
        allow_origins=list(resolved_settings.allowed_origins),
        allow_credentials=resolved_settings.allowed_origins != ("*",),
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type", "X-Request-ID"],
    )

    @application.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        started = time.monotonic()
        response = await call_next(request)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        response.headers["X-Request-ID"] = request_id
        response.headers["Server-Timing"] = f"app;dur={elapsed_ms}"
        logger.info(
            "request_id=%s method=%s path=%s status=%s duration_ms=%s",
            request_id,
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
        )
        return response

    application.include_router(create_router(resolved_settings, store, agora))
    application.include_router(
        create_mcp_bridge_router(resolved_settings, live_stats_store, session_history_store)
    )
    application.include_router(create_live_router(resolved_settings, live_room_store, agora))
    # Mounted last so it doesn't shadow any /v1/... route above. Reachable
    # at POST/GET <base_url>/mcp -- this must be a publicly reachable URL
    # for Agora's engine to call, so on local dev it needs the same
    # tunnel used for the rest of the backend (see docs/local-tunnels.md
    # and MCP_TOOL_SERVER_PUBLIC_URL in config.py).
    application.mount("/mcp", mcp_tool_server.streamable_http_app())
    return application


app = create_app()
