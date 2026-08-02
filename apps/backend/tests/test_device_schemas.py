"""Internal Device API schema contract tests."""

from typing import Any

import pytest
from pydantic import ValidationError

from app.schemas.common import OkResponse
from app.schemas.device import (
    DeviceExecutionError,
    DeviceRegisterData,
    DeviceRegisterRequest,
    DeviceReportData,
    DeviceReportRequest,
    ExecutionState,
)

REPORT_BASE: dict[str, Any] = {
    "user_id": "user-123",
    "device_id": "android-device-123",
    "request_id": "550e8400-e29b-41d4-a716-446655440008",
    "action": "contact_call",
    "timestamp": "2026-08-02T10:00:00Z",
}
RESULT = {
    "call_state": "calling",
    "contact_name": "Nguyễn Văn A",
    "phone_number": "***789",
}
EXECUTION_ERROR = DeviceExecutionError(
    code="CONTACT_NOT_FOUND",
    message="Contact not found",
    details={},
)


def test_device_registration_request_and_success_response() -> None:
    """Validate the documented Android registration exchange."""
    request = DeviceRegisterRequest.model_validate(
        {
            "user_id": "user-123",
            "device_id": "android-device-123",
            "platform": "android",
            "push_token": "fcm-token",
        }
    )
    response = OkResponse[DeviceRegisterData](data=DeviceRegisterData(device_id=request.device_id))

    assert response.model_dump(mode="json") == {
        "status": "ok",
        "data": {"device_id": "android-device-123", "registered": True},
    }


def test_device_success_report_and_acknowledgement() -> None:
    """Validate a successful action report and acknowledgement."""
    request = DeviceReportRequest.model_validate(
        REPORT_BASE
        | {
            "execution_state": ExecutionState.SUCCEEDED,
            "result": RESULT,
            "error": None,
        }
    )
    response = OkResponse[DeviceReportData](data=DeviceReportData(request_id=request.request_id))

    assert response.model_dump(mode="json") == {
        "status": "ok",
        "data": {
            "request_id": "550e8400-e29b-41d4-a716-446655440008",
            "report_received": True,
        },
    }


def test_device_failed_report_validates() -> None:
    """Accept a failed report with null result and a structured error."""
    request = DeviceReportRequest.model_validate(
        REPORT_BASE
        | {
            "execution_state": ExecutionState.FAILED,
            "result": None,
            "error": EXECUTION_ERROR,
        }
    )

    assert request.error is not None
    assert request.error.code == "CONTACT_NOT_FOUND"


@pytest.mark.parametrize(
    ("execution_state", "result", "error"),
    [
        (ExecutionState.SUCCEEDED, None, None),
        (ExecutionState.SUCCEEDED, RESULT, EXECUTION_ERROR),
        (ExecutionState.FAILED, None, None),
        (ExecutionState.FAILED, RESULT, EXECUTION_ERROR),
    ],
)
def test_device_report_rejects_payload_inconsistent_with_execution_state(
    execution_state: ExecutionState,
    result: dict[str, str] | None,
    error: DeviceExecutionError | None,
) -> None:
    """Reject result/error combinations that contradict execution state."""
    with pytest.raises(ValidationError):
        DeviceReportRequest.model_validate(
            REPORT_BASE
            | {
                "execution_state": execution_state,
                "result": result,
                "error": error,
            }
        )
