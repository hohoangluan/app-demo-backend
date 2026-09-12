"""Internal Android Device API schemas from the published contract."""

import re
from enum import StrEnum
from typing import Literal, Self
from uuid import UUID

from pydantic import BaseModel, ConfigDict, JsonValue, model_validator

from app.actions import Action
from app.schemas.common import AwareUtcDatetime, TrimmedNonEmptyStr

_PHONE_LIKE_DIGITS = 5
_INVALID_TAIL = "number_tail must contain exactly 3 or 4 digits"
_PHONE_LIKE_NAME = "caller name must not contain a phone number"
_CALLER_REQUIRED = "call_incoming requires caller"
_CALLER_FORBIDDEN = "call_ended must not include caller"


class DevicePlatform(StrEnum):
    """Platforms supported by the prototype Device API."""

    ANDROID = "android"


class DeviceRegisterRequest(BaseModel):
    """Register or update an Android device delivery token."""

    model_config = ConfigDict(extra="forbid")

    user_id: TrimmedNonEmptyStr
    device_id: TrimmedNonEmptyStr
    platform: Literal[DevicePlatform.ANDROID]
    push_token: TrimmedNonEmptyStr


class DeviceRegisterData(BaseModel):
    """Successful device registration result."""

    model_config = ConfigDict(extra="forbid")

    device_id: str
    registered: Literal[True] = True


class ExecutionState(StrEnum):
    """Terminal execution states reported by Android."""

    SUCCEEDED = "succeeded"
    FAILED = "failed"


class DeviceExecutionError(BaseModel):
    """Action execution error reported by the device."""

    model_config = ConfigDict(extra="forbid")

    code: str
    message: str
    details: dict[str, JsonValue]


class DeviceReportRequest(BaseModel):
    """Report the terminal outcome of a device action."""

    model_config = ConfigDict(extra="forbid")

    user_id: TrimmedNonEmptyStr
    device_id: TrimmedNonEmptyStr
    request_id: UUID
    action: Action
    execution_state: ExecutionState
    result: dict[str, JsonValue] | None = None
    error: DeviceExecutionError | None = None
    timestamp: AwareUtcDatetime

    @model_validator(mode="after")
    def validate_execution_payload(self) -> Self:
        """Match result and error presence to the reported execution state."""
        if self.execution_state is ExecutionState.SUCCEEDED and (
            self.result is None or self.error is not None
        ):
            message = "succeeded reports require a result and null error"
            raise ValueError(message)
        if self.execution_state is ExecutionState.FAILED and (
            self.result is not None or self.error is None
        ):
            message = "failed reports require null result and an error"
            raise ValueError(message)
        return self


class DeviceReportData(BaseModel):
    """Successful device report acknowledgement."""

    model_config = ConfigDict(extra="forbid")

    request_id: UUID
    report_received: Literal[True] = True


class IncomingCaller(BaseModel):
    """Caller identity after the phone has removed the full phone number."""

    model_config = ConfigDict(extra="forbid")

    contact_id: str | None = None
    name: str | None = None
    number_tail: str | None = None
    duplicate_name: bool = False

    @model_validator(mode="after")
    def enforce_privacy_boundary(self) -> Self:
        """Accept only a 3--4 digit tail and reject phone-like display names."""
        if self.number_tail is not None and not re.fullmatch(r"\d{3,4}", self.number_tail):
            raise ValueError(_INVALID_TAIL)
        if self.name is not None and len(re.sub(r"\D", "", self.name)) >= _PHONE_LIKE_DIGITS:
            raise ValueError(_PHONE_LIKE_NAME)
        return self


class DeviceEventRequest(BaseModel):
    """Spontaneous Android event; deliberately has no action request_id."""

    model_config = ConfigDict(extra="forbid")

    device_id: TrimmedNonEmptyStr
    type: Literal["call_incoming", "call_ended"]
    caller: IncomingCaller | None = None

    @model_validator(mode="after")
    def require_caller_for_incoming(self) -> Self:
        """Keep call_incoming and call_ended payloads unambiguous."""
        if self.type == "call_incoming" and self.caller is None:
            raise ValueError(_CALLER_REQUIRED)
        if self.type == "call_ended" and self.caller is not None:
            raise ValueError(_CALLER_FORBIDDEN)
        return self


class DeviceEventData(BaseModel):
    """Acknowledgement after an event has reached the glasses server."""

    model_config = ConfigDict(extra="forbid")

    device_id: str
    glasses_device_id: str
    event_forwarded: Literal[True] = True
