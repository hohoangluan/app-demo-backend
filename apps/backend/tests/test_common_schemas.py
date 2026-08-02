"""Common Public API response contract tests."""

from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.actions import Operation
from app.schemas.common import (
    AcceptedData,
    AcceptedResponse,
    ErrorResponse,
    OkResponse,
    PublicError,
    RequestState,
    RequestStatusData,
)
from app.schemas.service_results import MusicVolumeResult

REQUEST_ID = UUID("550e8400-e29b-41d4-a716-446655440004")
NOW = datetime(2026, 8, 2, 10, 0, tzinfo=UTC)


def test_accepted_response_matches_public_envelope() -> None:
    """Serialize the documented 202 accepted response shape."""
    response = AcceptedResponse(
        data=AcceptedData(
            request_id=REQUEST_ID,
            operation=Operation.MUSIC_VOLUME,
            status_url=f"/api/v1/requests/{REQUEST_ID}",
            accepted_at=NOW,
        )
    )

    assert response.model_dump(mode="json") == {
        "status": "ok",
        "data": {
            "request_id": str(REQUEST_ID),
            "operation": "music_volume",
            "request_state": "processing",
            "status_url": f"/api/v1/requests/{REQUEST_ID}",
            "accepted_at": "2026-08-02T10:00:00Z",
        },
    }


def test_synchronous_error_response_matches_public_envelope() -> None:
    """Serialize the documented synchronous error response shape."""
    response = ErrorResponse(
        error=PublicError(
            code="INVALID_REQUEST",
            message="destination is required",
            details={},
        )
    )

    assert response.model_dump(mode="json") == {
        "status": "error",
        "error": {
            "code": "INVALID_REQUEST",
            "message": "destination is required",
            "details": {},
        },
    }


@pytest.mark.parametrize(
    ("request_state", "payload"),
    [
        (RequestState.PROCESSING, {}),
        (
            RequestState.SUCCEEDED,
            {"result": MusicVolumeResult(volume_state="changed", level=70)},
        ),
        (
            RequestState.FAILED,
            {"error": PublicError(code="INVALID_VOLUME", message="Invalid", details={})},
        ),
        (
            RequestState.TIMED_OUT,
            {"error": PublicError(code="REPORT_TIMEOUT", message="Timed out", details={})},
        ),
    ],
)
def test_status_data_accepts_every_documented_request_state(
    request_state: RequestState,
    payload: dict[str, Any],
) -> None:
    """Represent every state published by the Request Status API."""
    status_data = RequestStatusData[MusicVolumeResult](
        request_id=REQUEST_ID,
        operation=Operation.MUSIC_VOLUME,
        request_state=request_state,
        created_at=NOW,
        updated_at=NOW,
        **payload,
    )

    response = OkResponse(data=status_data)

    assert response.data.request_state is request_state


def test_status_data_allows_error_for_failed_request() -> None:
    """Attach a handler error to a failed request representation."""
    status_data = RequestStatusData[MusicVolumeResult](
        request_id=REQUEST_ID,
        operation=Operation.MUSIC_VOLUME,
        request_state=RequestState.FAILED,
        error=PublicError(code="INVALID_VOLUME", message="Invalid volume", details={}),
        created_at=NOW,
        updated_at=NOW,
    )

    assert status_data.error is not None
    assert status_data.error.code == "INVALID_VOLUME"


@pytest.mark.parametrize(
    ("request_state", "payload"),
    [
        (
            RequestState.PROCESSING,
            {"result": MusicVolumeResult(volume_state="changed", level=70)},
        ),
        (RequestState.SUCCEEDED, {}),
        (
            RequestState.SUCCEEDED,
            {"error": PublicError(code="INVALID_VOLUME", message="Invalid", details={})},
        ),
        (RequestState.FAILED, {}),
        (
            RequestState.TIMED_OUT,
            {
                "result": MusicVolumeResult(volume_state="changed", level=70),
                "error": PublicError(code="REPORT_TIMEOUT", message="Timed out", details={}),
            },
        ),
    ],
)
def test_status_data_rejects_payload_inconsistent_with_state(
    request_state: RequestState,
    payload: dict[str, Any],
) -> None:
    """Reject result/error combinations that contradict their request state."""
    with pytest.raises(ValidationError):
        RequestStatusData[MusicVolumeResult](
            request_id=REQUEST_ID,
            operation=Operation.MUSIC_VOLUME,
            request_state=request_state,
            created_at=NOW,
            updated_at=NOW,
            **payload,
        )
