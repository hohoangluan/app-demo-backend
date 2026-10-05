"""Common Public API envelopes and request status schemas."""

from datetime import UTC, datetime
from enum import StrEnum
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, JsonValue, model_validator

from app.actions import Operation

# Reusable coordinate constraints shared by every request/result schema that
# carries a latitude or longitude, so the bounds are defined exactly once.
Latitude = Annotated[float, Field(ge=-90, le=90)]
Longitude = Annotated[float, Field(ge=-180, le=180)]


def _trim_and_reject_empty(value: str) -> str:
    """Trim surrounding whitespace and reject empty/whitespace-only strings.

    The trimmed value is what gets stored: callers that submit
    ``" user-1 "`` end up with ``"user-1"``, not the original untrimmed
    string.
    """
    trimmed = value.strip()
    if not trimmed:
        message = "value must not be empty or whitespace-only"
        raise ValueError(message)
    return trimmed


# Reusable type for client-supplied free-text request fields: trims
# whitespace and rejects the result if it is empty.
TrimmedNonEmptyStr = Annotated[str, AfterValidator(_trim_and_reject_empty)]


def _require_aware_utc(value: datetime) -> datetime:
    """Reject naive datetimes and normalize aware datetimes to UTC.

    A missing UTC offset is rejected outright. A datetime with a non-UTC
    offset is accepted and converted to the equivalent UTC instant (the
    offset is not rejected) so every stored/serialized datetime uses a
    single, consistent UTC representation.
    """
    if value.tzinfo is None:
        message = "datetime must be timezone-aware"
        raise ValueError(message)
    return value.astimezone(UTC)


# Reusable type requiring a timezone-aware datetime, normalized to UTC.
AwareUtcDatetime = Annotated[datetime, AfterValidator(_require_aware_utc)]


class RequestState(StrEnum):
    """Public request lifecycle states."""

    PROCESSING = "processing"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    TIMED_OUT = "timed_out"


class PublicError(BaseModel):
    """Stable error representation shared by Public responses."""

    model_config = ConfigDict(extra="forbid")

    code: str
    message: str
    details: dict[str, JsonValue]


class AcceptedData(BaseModel):
    """Data returned after a function request is accepted."""

    model_config = ConfigDict(extra="forbid")

    request_id: UUID
    operation: Operation
    request_state: Literal[RequestState.PROCESSING] = RequestState.PROCESSING
    status_url: str
    accepted_at: AwareUtcDatetime


class OkResponse[DataT](BaseModel):
    """Successful Public API envelope."""

    model_config = ConfigDict(extra="forbid")

    status: Literal["ok"] = "ok"
    data: DataT


class ErrorResponse(BaseModel):
    """Synchronous Public API error envelope."""

    model_config = ConfigDict(extra="forbid")

    status: Literal["error"] = "error"
    error: PublicError


class RequestStatusData[ResultT](BaseModel):
    """Public request status representation without a callback envelope decision."""

    model_config = ConfigDict(extra="forbid")

    request_id: UUID
    operation: Operation
    request_state: RequestState
    result: ResultT | None = None
    error: PublicError | None = None
    created_at: AwareUtcDatetime
    updated_at: AwareUtcDatetime

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
