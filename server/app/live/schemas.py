from __future__ import annotations

from pydantic import BaseModel, Field


class CreateLiveRoomRequest(BaseModel):
    speaker_rtc_uid: int = Field(ge=1, le=2_147_483_647)
    speaker_display_name: str = Field(min_length=1, max_length=64)
    topic_title: str = Field(min_length=1, max_length=200)


class LiveParticipantInfo(BaseModel):
    rtc_uid: int
    display_name: str
    role: str


class CreateLiveRoomResponse(BaseModel):
    room_id: str
    channel_name: str
    app_id: str
    rtc_token: str
    rtc_uid: int
    role: str = "speaker"


class JoinLiveRoomRequest(BaseModel):
    room_id: str = Field(min_length=1, max_length=64)
    rtc_uid: int = Field(ge=1, le=2_147_483_647)
    display_name: str = Field(min_length=1, max_length=64)
    # "mentor" to co-host, "audience" to watch silently. A room can have
    # at most one mentor at a time -- see routes.py join_room.
    requested_role: str = Field(default="audience", pattern="^(mentor|audience)$")


class JoinLiveRoomResponse(BaseModel):
    room_id: str
    channel_name: str
    app_id: str
    rtc_token: str
    rtc_uid: int
    role: str
    topic_title: str
    participant_count: int


class LeaveLiveRoomRequest(BaseModel):
    room_id: str = Field(min_length=1, max_length=64)
    rtc_uid: int = Field(ge=1, le=2_147_483_647)


class PromoteParticipantRequest(BaseModel):
    room_id: str = Field(min_length=1, max_length=64)
    requester_rtc_uid: int = Field(ge=1, le=2_147_483_647, description="Must be the speaker.")
    target_rtc_uid: int = Field(ge=1, le=2_147_483_647)
    new_role: str = Field(pattern="^(mentor|audience)$")


class LiveRoomStateResponse(BaseModel):
    room_id: str
    channel_name: str
    topic_title: str
    speaker_rtc_uid: int
    participants: list[LiveParticipantInfo]
    coach_agent_active: bool


class ListLiveRoomsResponse(BaseModel):
    rooms: list[LiveRoomStateResponse]
