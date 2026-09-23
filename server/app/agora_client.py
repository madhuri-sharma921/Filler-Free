from __future__ import annotations

import secrets
import time
from typing import Any

import httpx
from agora_agent import Agent, Area, AsyncAgora, DeepgramSTT, MiniMaxTTS, OpenAI
from agora_agent.agentkit import generate_convo_ai_token
from agora_agent.agentkit.token import ROLE_PUBLISHER, ROLE_SUBSCRIBER, generate_rtc_token
from agora_agent.core.api_error import ApiError

from .config import Settings
from .mcp.live_stats_store import LiveStatsStore
from .mcp.session_history_store import SessionHistoryStore


DEFAULT_SYSTEM_PROMPT = """You are Ada, an agentic developer advocate from Agora. Help developers understand and build with Agora Conversational AI. Be concise and technically precise. If you do not know an Agora-specific fact, say so and suggest checking docs.agora.io."""


class AgoraUpstreamError(RuntimeError):
    pass


class AgoraTimeoutError(TimeoutError):
    pass


AREA_BY_NAME = {
    "NORTH_AMERICA": Area.US,
    "US": Area.US,
    "EUROPE": Area.EU,
    "EU": Area.EU,
    "ASIA_PACIFIC": Area.AP,
    "AP": Area.AP,
    "CHINA": Area.CN,
    "CN": Area.CN,
}


class AgoraClient:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._http = http_client or httpx.AsyncClient(timeout=httpx.Timeout(60.0))
        self._owns_http = http_client is None
        area_name = settings.agora_area.strip().upper()
        if area_name not in AREA_BY_NAME:
            supported = ", ".join(sorted(AREA_BY_NAME))
            raise ValueError(f"Unsupported AGORA_AREA '{settings.agora_area}'. Use one of: {supported}.")
        self._client = AsyncAgora(
            area=AREA_BY_NAME[area_name],
            app_id=settings.agora_app_id,
            app_certificate=settings.agora_app_certificate,
            httpx_client=self._http,
        )
        self._sessions: dict[str, tuple[str, Any]] = {}

    def create_user_tokens(self, channel_name: str, rtc_uid: int) -> tuple[str, str, int]:
        token = generate_convo_ai_token(
            app_id=self.settings.agora_app_id,
            app_certificate=self.settings.agora_app_certificate,
            channel_name=channel_name,
            uid=rtc_uid,
            token_expire=self.settings.token_expiry_seconds,
        )
        expires_at = int(time.time()) + self.settings.token_expiry_seconds
        return token, token, expires_at

    def create_ils_rtc_token(self, channel_name: str, rtc_uid: int, publisher: bool) -> tuple[str, int]:
        """RTC-only token for an Interactive Live Streaming room participant.

        Unlike the solo coaching channel (CHANNEL_PROFILE_COMMUNICATION,
        combined RTC+RTM token via create_user_tokens), an ILS room uses
        LIVE_BROADCASTING with an explicit publisher/subscriber role
        baked into the token itself: the speaker and mentor need
        ROLE_PUBLISHER (they publish audio), plain audience members need
        only ROLE_SUBSCRIBER (listen-only, matches how Android joins
        them with CLIENT_ROLE_AUDIENCE -- see LiveRoomSessionManager).
        """
        role = ROLE_PUBLISHER if publisher else ROLE_SUBSCRIBER
        token = generate_rtc_token(
            app_id=self.settings.agora_app_id,
            app_certificate=self.settings.agora_app_certificate,
            channel=channel_name,
            uid=rtc_uid,
            role=role,
            expiry_seconds=self.settings.token_expiry_seconds,
        )
        expires_at = int(time.time()) + self.settings.token_expiry_seconds
        return token, expires_at

    def _mcp_servers_config(self) -> list[dict[str, Any]] | None:
        """The `llm.mcp_servers` list Agora's engine needs to reach our tool
        server (see app/mcp/tool_server.py). Returns None when no public
        URL is configured, so `.with_llm(..., mcp_servers=None)` cleanly
        no-ops -- the agent just runs without tool-calling, same as before
        this feature existed.
        """
        base_url = self.settings.mcp_tool_server_public_url.rstrip("/")
        if not base_url or not self.settings.enable_agent_tool_calling:
            return None
        return [
            {
                "name": "filler_free_coach_tools",
                "url": f"{base_url}/mcp",
                "transport": "streamable-http",
            }
        ]

    def _build_agent(self, system_prompt: str | None = None) -> Agent:
        mcp_servers = self._mcp_servers_config()
        return (
            Agent(
                client=self._client,
                turn_detection={"language": "en-US"},
                advanced_features={
                    "enable_rtm": True,
                    # Enables tool invocation for whichever MCP servers are
                    # attached below (see release notes v2.4: MCP
                    # integration). No-ops harmlessly when mcp_servers is
                    # None -- there's simply nothing for the engine to call.
                    "enable_tools": mcp_servers is not None,
                },
                parameters={
                    "audio_scenario": "chorus",
                    "data_channel": "rtm",
                    "enable_error_message": True,
                    "enable_metrics": True,
                },
            )
            .with_stt(DeepgramSTT(model=self.settings.asr_model, language="en"))
            .with_llm(
                OpenAI(
                    model=self.settings.llm_model,
                    mcp_servers=mcp_servers,
                    system_messages=[
                        {"role": "system", "content": system_prompt or DEFAULT_SYSTEM_PROMPT}
                    ],
                    greeting_message="Hi there!",
                    failure_message="Please wait a moment.",
                    max_history=15,
                    max_tokens=1024,
                    temperature=0.7,
                    top_p=0.95,
                )
            )
            .with_tts(
                MiniMaxTTS(
                    model=self.settings.tts_model,
                    voice_id=self.settings.tts_voice_id,
                )
            )
        )

    async def join_agent(
        self,
        channel_name: str,
        requester_rtc_uid: int,
        agent_profile: str | None = None,
        system_prompt: str | None = None,
        agent_rtc_uid: int | None = None,
    ) -> dict[str, Any]:
        resolved_agent_uid = agent_rtc_uid if agent_rtc_uid is not None else self.settings.agent_uid
        session = self._build_agent(system_prompt).create_async_session(
            channel=channel_name,
            agent_uid=str(resolved_agent_uid),
            remote_uids=[str(requester_rtc_uid)],
            name=f"android-server-agent-{int(time.time())}-{secrets.randbelow(9000) + 1000}",
            idle_timeout=30,
            preset=agent_profile,
            expires_in=self.settings.token_expiry_seconds,
            debug=False,
        )
        try:
            agent_id = await session.start()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError, ValueError) as exc:
            raise AgoraUpstreamError(f"Agora Conversational AI start failed: {exc}") from exc
        if not agent_id:
            raise AgoraUpstreamError("Agora response did not include agent_id.")
        self._sessions[agent_id] = (channel_name, session)
        return {
            "agent_id": agent_id,
            "create_ts": int(time.time()),
            "status": "started",
            "rtc_uid": resolved_agent_uid,
        }

    async def interrupt_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.interrupt()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora interrupt request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent interrupt failed: {exc}") from exc

    async def leave_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.stop()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora stop request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent stop failed: {exc}") from exc
        self._sessions.pop(agent_id, None)

    def _require_session(self, agent_id: str, channel_name: str) -> Any:
        active = self._sessions.get(agent_id)
        if active is None or active[0] != channel_name:
            raise AgoraUpstreamError("The Agora agent session is not active in this server process.")
        return active[1]

    async def close(self) -> None:
        if self._owns_http:
            await self._http.aclose()