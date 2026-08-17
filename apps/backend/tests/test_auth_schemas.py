"""Demo phone-app auth API schema contract tests."""

import pytest
from pydantic import ValidationError

from app.schemas.auth import (
    DeviceLinkRequest,
    LoginRequest,
    OtpVerifyRequest,
    RegisterRequest,
)


def test_register_request_normalizes_phone_number() -> None:
    """Strip spacing from a phone number into a compact digit string."""
    request = RegisterRequest.model_validate(
        {"phone_number": "090 123 4567", "password": "correct-horse"}
    )
    assert request.phone_number == "0901234567"


def test_register_request_normalizes_plus_prefixed_phone_number() -> None:
    """Preserve a leading `+` while stripping spacing."""
    request = RegisterRequest.model_validate(
        {"phone_number": "+84 90 123 4567", "password": "correct-horse"}
    )
    assert request.phone_number == "+84901234567"


def test_register_request_rejects_too_short_phone_number() -> None:
    """Reject a phone number with fewer than 8 digits."""
    with pytest.raises(ValidationError):
        RegisterRequest.model_validate({"phone_number": "090", "password": "correct-horse"})


def test_register_request_rejects_short_password() -> None:
    """Reject a password shorter than the minimum length."""
    with pytest.raises(ValidationError):
        RegisterRequest.model_validate({"phone_number": "0901234567", "password": "123"})


def test_register_request_rejects_unknown_field() -> None:
    """Reject a request body containing an undocumented field."""
    with pytest.raises(ValidationError):
        RegisterRequest.model_validate(
            {"phone_number": "0901234567", "password": "correct-horse", "unexpected": "x"}
        )


def test_register_request_accepts_optional_display_name() -> None:
    """Accept an optional `display_name` field."""
    request = RegisterRequest.model_validate(
        {
            "phone_number": "0901234567",
            "password": "correct-horse",
            "display_name": "Nguyễn Văn A",
        }
    )
    assert request.display_name == "Nguyễn Văn A"


def test_otp_verify_request_normalizes_phone_and_trims_code() -> None:
    """Normalize the phone number and trim whitespace from the OTP code."""
    request = OtpVerifyRequest.model_validate(
        {"phone_number": "090 123 4567", "otp_code": " 123456 "}
    )
    assert request.phone_number == "0901234567"
    assert request.otp_code == "123456"


def test_login_request_rejects_short_password() -> None:
    """Reject a login password shorter than the minimum length."""
    with pytest.raises(ValidationError):
        LoginRequest.model_validate({"phone_number": "0901234567", "password": "123"})


def test_device_link_request_rejects_empty_device_id() -> None:
    """Reject a device_id that is empty after trimming."""
    with pytest.raises(ValidationError):
        DeviceLinkRequest.model_validate({"device_id": "   "})


def test_device_link_request_trims_device_id() -> None:
    """Trim surrounding whitespace from device_id."""
    request = DeviceLinkRequest.model_validate({"device_id": " device-100 "})
    assert request.device_id == "device-100"
