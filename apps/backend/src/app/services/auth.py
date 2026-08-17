"""Demo phone-app auth service: register, OTP verify, login, logout, device link.

Mirrors `OperationService`'s boundary: computes business decisions and calls
repository primitives, never builds an HTTP response and never commits the
caller's session -- the router owns the transaction for its use case.

`public_user_id` (a short code, distinct from the internal UUID primary key)
is what ties an authenticated app account to a `devices` row: the same
string a person types as `user_id` on the paired Android device's own
`POST /api/v1/device/register` call. This service never creates or mutates a
`devices` row -- that registration remains the Android app's job -- it only
confirms an already-active device belongs to the caller.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from app.errors import (
    DeviceNotFoundError,
    DeviceOwnerConflictError,
    InvalidCredentialsError,
    OtpExpiredError,
    OtpInvalidError,
    PhoneAlreadyRegisteredError,
    PhoneNotVerifiedError,
)
from app.models.enums import DeviceStatus
from app.repositories.session import SessionRepository
from app.repositories.user import PhoneAlreadyRegistered, UserRegistrationRequest, UserRepository
from app.security import (
    generate_otp_code,
    generate_public_user_id,
    generate_session_token,
    hash_secret,
    hash_token,
    verify_secret,
)

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.models.device import Device
    from app.models.user import User
    from app.repositories.device import DeviceRepository

_OTP_TTL = timedelta(minutes=5)
_OTP_MAX_ATTEMPTS = 5
_SESSION_TTL = timedelta(days=30)

_PHONE_ALREADY_REGISTERED_MSG = "Phone number is already registered"
_OTP_NOT_PENDING_MSG = "No pending OTP for this phone number"
_OTP_EXPIRED_MSG = "OTP code has expired"
_OTP_INVALID_MSG = "OTP code does not match"
_INVALID_CREDENTIALS_MSG = "Phone number or password is incorrect"
_PHONE_NOT_VERIFIED_MSG = "Phone number has not completed OTP verification"
_DEVICE_NOT_FOUND_MSG = "No active device found for this device_id"
_DEVICE_OWNER_CONFLICT_MSG = "Device is linked to a different account"


@dataclass(frozen=True, slots=True)
class IssuedSession:
    """A freshly issued session: the raw token is only ever available here."""

    raw_token: str
    user: User


class AuthService:
    """Business rules for the demo phone-app auth subsystem."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the service to a caller-owned session/transaction."""
        self._session = session
        self._users = UserRepository(session)
        self._sessions = SessionRepository(session)

    async def register(
        self, *, phone_number: str, password: str, display_name: str | None
    ) -> tuple[User, str]:
        """Register (or resend OTP for) `phone_number`.

        Returns `(user, raw_otp_code)`; the raw OTP is never persisted, only
        its hash is -- the caller is responsible for delivering it (this
        demo backend logs it server-side; there is no SMS provider wired
        up).

        Raises `PhoneAlreadyRegisteredError` if the phone already completed
        OTP verification.
        """
        otp_code = generate_otp_code()
        request = UserRegistrationRequest(
            phone_number=phone_number,
            public_user_id=generate_public_user_id(),
            password_hash=hash_secret(password),
            display_name=display_name,
            otp_hash=hash_secret(otp_code),
            otp_expires_at=datetime.now(UTC) + _OTP_TTL,
        )
        outcome = await self._users.register(request)
        if isinstance(outcome, PhoneAlreadyRegistered):
            raise PhoneAlreadyRegisteredError(
                _PHONE_ALREADY_REGISTERED_MSG,
                details={"phone_number": phone_number},
            )
        return outcome.user, otp_code

    async def verify_otp(self, *, phone_number: str, otp_code: str) -> IssuedSession:
        """Verify a pending OTP code for `phone_number` and issue a session on success."""
        user = await self._users.get_by_phone_locked(phone_number)
        if user is None or user.otp_hash is None or user.otp_expires_at is None:
            raise OtpExpiredError(_OTP_NOT_PENDING_MSG)
        if user.otp_expires_at < datetime.now(UTC) or user.otp_attempts >= _OTP_MAX_ATTEMPTS:
            raise OtpExpiredError(_OTP_EXPIRED_MSG)
        if not verify_secret(otp_code, user.otp_hash):
            user.otp_attempts += 1
            await self._session.flush()
            raise OtpInvalidError(_OTP_INVALID_MSG)

        user.phone_verified = True
        user.otp_hash = None
        user.otp_expires_at = None
        user.otp_attempts = 0
        await self._session.flush()
        return await self._issue_session(user)

    async def login(self, *, phone_number: str, password: str) -> IssuedSession:
        """Log in a verified user.

        Raises `InvalidCredentialsError` for an unknown phone or wrong
        password (one error for both, so a caller cannot enumerate
        registered phone numbers), or `PhoneNotVerifiedError` if the account
        never completed OTP verification.
        """
        user = await self._users.get_by_phone(phone_number)
        if user is None or not verify_secret(password, user.password_hash):
            raise InvalidCredentialsError(_INVALID_CREDENTIALS_MSG)
        if not user.phone_verified:
            raise PhoneNotVerifiedError(_PHONE_NOT_VERIFIED_MSG)
        return await self._issue_session(user)

    async def logout(self, *, user_id: UUID) -> None:
        """Revoke every active session for `user_id` (logout-everywhere semantics)."""
        await self._sessions.revoke_all_for_user(user_id)

    async def _issue_session(self, user: User) -> IssuedSession:
        raw_token = generate_session_token()
        await self._sessions.create(
            user_id=user.id,
            token_hash=hash_token(raw_token),
            expires_at=datetime.now(UTC) + _SESSION_TTL,
        )
        return IssuedSession(raw_token=raw_token, user=user)


async def confirm_device_link(
    device_repository: DeviceRepository, *, public_user_id: str, device_id: str
) -> Device:
    """Confirm an already-registered active device belongs to `public_user_id`.

    Raises `DeviceNotFoundError` when no active device row exists for
    `device_id`, or `DeviceOwnerConflictError` when it is active under a
    different `public_user_id`. Never creates or mutates a device row.
    """
    device = await device_repository.get_by_device_id(device_id)
    if device is None or device.status is not DeviceStatus.ACTIVE:
        raise DeviceNotFoundError(_DEVICE_NOT_FOUND_MSG, details={"device_id": device_id})
    if device.user_id != public_user_id:
        raise DeviceOwnerConflictError(_DEVICE_OWNER_CONFLICT_MSG, details={"device_id": device_id})
    return device
