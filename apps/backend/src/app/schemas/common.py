"""Common Public API envelopes and request status schemas."""

from datetime import datetime
from enum import StrEnum
from typing import Literal, Self
from uuid import UUID

from pydantic import BaseModel, JsonValue, model_validator

from app.actions import Operation


class RequestState(StrEnum):
    """Public request lifecycle states."""

    PROCESSING = "processing"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    TIMED_OUT = "timed_out"


class PublicError(BaseModel):
    """Stable error representation shared by Public responses."""

    code: str
    message: str
    details: dict[str, JsonValue]


class AcceptedData(BaseModel):
    """Data returned after a function request is accepted."""

    request_id: UUID
    operation: Operation
    request_state: Literal[RequestState.PROCESSING] = RequestState.PROCESSING
    status_url: str
    accepted_at: datetime


class OkResponse[DataT](BaseModel):
    """Successful Public API envelope."""

    status: Literal["ok"] = "ok"
    data: DataT


class ErrorResponse(BaseModel):
    """Synchronous Public API error envelope."""

    status: Literal["error"] = "error"
    error: PublicError


class RequestStatusData[ResultT](BaseModel):
    """Public request status representation without a callback envelope decision."""

    request_id: UUID
    operation: Operation
    request_state: RequestState
    result: ResultT | None = None
    error: PublicError | None = None
    created_at: datetime
    updated_at: datetime

    @model_validator(mode="after")
    def validate_state_payload(self) -> Self:
        """Match result and error presence to the published request state."""
        if self.request_state is RequestState.PROCESSING and (
            self.result is not None or self.error is not None
        ):
            message = "processing requests require null result and error"
            raise ValueError(message)
        if self.request_state is RequestState.SUCCEEDED and (
            self.result is None or self.error is not None
        ):
            message = "succeeded requests require a result and null error"
            raise ValueError(message)
        if self.request_state in {RequestState.FAILED, RequestState.TIMED_OUT} and (
            self.result is not None or self.error is None
        ):
            message = "failed and timed_out requests require null result and an error"
            raise ValueError(message)
        return self


AcceptedResponse = OkResponse[AcceptedData]
