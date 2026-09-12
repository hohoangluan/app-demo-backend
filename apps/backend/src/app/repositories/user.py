"""User table persistence primitives: register (with resend-before-verify) and lookup.

`UserRepository` issues statements against the `AsyncSession` it is given and
never calls `session.commit()`; the caller (a future service layer) owns the
transaction boundary for its use case, mirroring `DeviceRepository`.

Registration policy: a phone number that already completed OTP verification
cannot be re-registered (`PhoneAlreadyRegistered`). A phone number that
registered but never verified can register again -- this is the resend-OTP
path, not a duplicate-account path -- and simply overwrites the pending
row's password/OTP material in place.
"""

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User


@dataclass(frozen=True, slots=True)
class UserRegistrationRequest:
    """Input for `UserRepository.register`.

    `public_user_id` is only applied when inserting a brand-new row; an
    existing unverified row keeps its original `public_user_id` on resend.
    """

    phone_number: str
    public_user_id: str
    password_hash: str
    display_name: str | None
    otp_hash: str
    otp_expires_at: datetime


@dataclass(frozen=True, slots=True)
class UserRegistered:
    """A new (or previously-unverified) user row is ready for OTP verification."""

    user: User


@dataclass(frozen=True, slots=True)
class PhoneAlreadyRegistered:
    """The phone number already completed OTP verification."""

    phone_number: str


type UserRegisterOutcome = UserRegistered | PhoneAlreadyRegistered


class UserRepository:
    """Persistence primitives for `users`: register and lookup."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to a caller-owned session/transaction."""
        self._session = session

    async def register(self, request: UserRegistrationRequest) -> UserRegisterOutcome:
        """Insert a new user row, or refresh a pending (unverified) one in place.

        Does not commit; the caller owns the transaction boundary.
        """
        result = await self._session.execute(
            select(User).where(User.phone_number == request.phone_number).with_for_update()
        )
        existing = result.scalar_one_or_none()

        if existing is not None:
            if existing.phone_verified:
                return PhoneAlreadyRegistered(phone_number=request.phone_number)
            existing.password_hash = request.password_hash
            existing.display_name = request.display_name
            existing.otp_hash = request.otp_hash
            existing.otp_expires_at = request.otp_expires_at
            existing.otp_attempts = 0
            await self._session.flush()
            return UserRegistered(user=existing)

        user = User(
            phone_number=request.phone_number,
            public_user_id=request.public_user_id,
            display_name=request.display_name,
            password_hash=request.password_hash,
            phone_verified=False,
            otp_hash=request.otp_hash,
            otp_expires_at=request.otp_expires_at,
            otp_attempts=0,
        )
        self._session.add(user)
        await self._session.flush()
        return UserRegistered(user=user)

    async def get_by_phone(self, phone_number: str) -> User | None:
        """Return the user row for `phone_number`, or `None`."""
        result = await self._session.execute(select(User).where(User.phone_number == phone_number))
        return result.scalar_one_or_none()

    async def get_by_phone_locked(self, phone_number: str) -> User | None:
        """Return the user row for `phone_number` locked `FOR UPDATE`, or `None`."""
        result = await self._session.execute(
            select(User).where(User.phone_number == phone_number).with_for_update()
        )
        return result.scalar_one_or_none()

    async def get_by_id(self, user_id: UUID) -> User | None:
        """Return the user row for `user_id`, or `None`."""
        return await self._session.get(User, user_id)

    async def get_by_public_user_id(self, public_user_id: str) -> User | None:
        """Return the account paired through a human-facing public user id."""
        result = await self._session.execute(
            select(User).where(User.public_user_id == public_user_id)
        )
        return result.scalar_one_or_none()

    async def update_preferences(  # noqa: PLR0913
        self,
        user_id: UUID,
        *,
        font_size_option: str,
        voice_option: str,
        high_contrast: bool,
        haptics_enabled: bool,
        announce_caller: str,
    ) -> User | None:
        """Overwrite `user_id`'s accessibility preferences in place. Does not commit."""
        user = await self._session.get(User, user_id)
        if user is None:
            return None
        user.font_size_option = font_size_option
        user.voice_option = voice_option
        user.high_contrast = high_contrast
        user.haptics_enabled = haptics_enabled
        user.announce_caller = announce_caller
        await self._session.flush()
        return user
