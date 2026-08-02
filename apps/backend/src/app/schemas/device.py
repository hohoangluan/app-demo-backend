"""Internal Android Device API schemas from the published contract."""

from datetime import datetime
from enum import StrEnum
from typing import Literal, Self
from uuid import UUID

from pydantic import BaseModel, JsonValue, model_validator

from app.actions import Action


class DevicePlatform(StrEnum):
    """Platforms supported by the prototype Device API."""

    ANDROID = "android"


class DeviceRegisterRequest(BaseModel):
    """Register or update an Android device delivery token."""

    user_id: str
    device_id: str
    platform: Literal[DevicePlatform.ANDROID]
    push_token: str


class DeviceRegisterData(BaseModel):
    """Successful device registration result."""

    device_id: str
    registered: Literal[True] = True


class ExecutionState(StrEnum):
    """Terminal execution states reported by Android."""

    SUCCEEDED = "succeeded"
    FAILED = "failed"


class DeviceExecutionError(BaseModel):
    """Action execution error reported by the device."""

    code: str
    message: str
    details: dict[str, JsonValue]


class DeviceReportRequest(BaseModel):
    """Report the terminal outcome of a device action."""

    user_id: str
    device_id: str
    request_id: UUID
    action: Action
    execution_state: ExecutionState
    result: dict[str, JsonValue] | None = None
    error: DeviceExecutionError | None = None
    timestamp: datetime

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

    request_id: UUID
    report_received: Literal[True] = True
