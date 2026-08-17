"""Demo phone-app auth API schemas: register, OTP verify, login, device link."""

import re
from typing import Annotated, Literal
from uuid import UUID

from pydantic import AfterValidator, BaseModel, ConfigDict, Field

from app.schemas.common import TrimmedNonEmptyStr

# Strips everything except digits and a leading "+" so "090 123 4567" and
# "0901234567" normalize to the same stored/compared value.
_PHONE_STRIP_RE = re.compile(r"[^0-9+]")
_MIN_PHONE_DIGITS = 8


def _normalize_phone(value: str) -> str:
    """Normalize a user-entered phone number to a stable comparison form."""
    cleaned = _PHONE_STRIP_RE.sub("", value.strip())
    if len(cleaned) < _MIN_PHONE_DIGITS:
        message = f"phone_number must contain at least {_MIN_PHONE_DIGITS} digits"
        raise ValueError(message)
    return cleaned


NormalizedPhone = Annotated[str, AfterValidator(_normalize_phone)]


class RegisterRequest(BaseModel):
    """Register (or resend OTP for) a phone number + password account."""

    model_config = ConfigDict(extra="forbid")

    phone_number: NormalizedPhone
    password: Annotated[str, Field(min_length=6, max_length=200)]
    display_name: TrimmedNonEmptyStr | None = None


class RegisterData(BaseModel):
    """Result of a successful registration: OTP was generated and must be verified."""

    model_config = ConfigDict(extra="forbid")

    user_id: UUID
    public_user_id: str
    phone_number: str
    otp_required: Literal[True] = True


class OtpVerifyRequest(BaseModel):
    """Verify a previously issued OTP code for a phone number."""

    model_config = ConfigDict(extra="forbid")

    phone_number: NormalizedPhone
    otp_code: TrimmedNonEmptyStr


class LoginRequest(BaseModel):
    """Log in with a verified phone number + password."""

    model_config = ConfigDict(extra="forbid")

    phone_number: NormalizedPhone
    password: Annotated[str, Field(min_length=6, max_length=200)]


class SessionData(BaseModel):
    """An issued session: the raw bearer token is returned exactly once."""

    model_config = ConfigDict(extra="forbid")

    access_token: str
    user_id: UUID
    public_user_id: str
    phone_number: str
    display_name: str | None = None


class LogoutData(BaseModel):
    """Result of revoking the caller's current session."""

    model_config = ConfigDict(extra="forbid")

    logged_out: Literal[True] = True


class DeviceLinkRequest(BaseModel):
    """Confirm pairing between the logged-in user and an already-registered device."""

    model_config = ConfigDict(extra="forbid")

    device_id: TrimmedNonEmptyStr


class DeviceLinkData(BaseModel):
    """Result of a successful device-link confirmation."""

    model_config = ConfigDict(extra="forbid")

    device_id: str
    platform: str
    linked: Literal[True] = True
