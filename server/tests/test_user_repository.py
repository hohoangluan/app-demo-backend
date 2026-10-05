"""PostgreSQL-backed integration tests for `UserRepository`/`SessionRepository`."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from app.repositories.session import SessionRepository
from app.repositories.user import (
    PhoneAlreadyRegistered,
    UserRegistered,
    UserRegistrationRequest,
    UserRepository,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


def _registration(*, phone_number: str, public_user_id: str) -> UserRegistrationRequest:
    return UserRegistrationRequest(
        phone_number=phone_number,
        public_user_id=public_user_id,
        password_hash="pbkdf2_sha256$1$00$00",
        display_name=None,
        otp_hash="pbkdf2_sha256$1$00$00",
        otp_expires_at=datetime.now(UTC) + timedelta(minutes=5),
    )


async def test_register_inserts_a_new_user_row(db_session: AsyncSession) -> None:
    """Insert a new unverified user row for a phone number seen for the first time."""
    repo = UserRepository(db_session)
    outcome = await repo.register(
        _registration(phone_number="0921111111", public_user_id="PUB0001")
    )
    await db_session.commit()

    assert isinstance(outcome, UserRegistered)
    assert outcome.user.phone_verified is False

    fetched = await repo.get_by_phone("0921111111")
    assert fetched is not None
    assert fetched.public_user_id == "PUB0001"


async def test_register_resends_otp_for_unverified_phone_without_conflict(
    db_session: AsyncSession,
) -> None:
    """Refresh the same pending row in place instead of rejecting a resend."""
    repo = UserRepository(db_session)
    first = await repo.register(_registration(phone_number="0922222222", public_user_id="PUB0002"))
    await db_session.commit()
    assert isinstance(first, UserRegistered)
    first_id = first.user.id

    second = await repo.register(_registration(phone_number="0922222222", public_user_id="PUB9999"))
    await db_session.commit()

    assert isinstance(second, UserRegistered)
    assert second.user.id == first_id
    # public_user_id is only assigned on first insert -- a resend never
    # overwrites the pairing code a person may have already been shown.
    assert second.user.public_user_id == "PUB0002"


async def test_register_rejects_already_verified_phone(db_session: AsyncSession) -> None:
    """Reject re-registering a phone number that already completed OTP verification."""
    repo = UserRepository(db_session)
    outcome = await repo.register(
        _registration(phone_number="0923333333", public_user_id="PUB0003")
    )
    await db_session.commit()
    assert isinstance(outcome, UserRegistered)

    user = outcome.user
    user.phone_verified = True
    await db_session.commit()

    conflict = await repo.register(
        _registration(phone_number="0923333333", public_user_id="PUB0004")
    )
    assert isinstance(conflict, PhoneAlreadyRegistered)


async def test_session_repository_round_trip(db_session: AsyncSession) -> None:
    """Create, look up, and revoke a session token end to end."""
    users = UserRepository(db_session)
    sessions = SessionRepository(db_session)

    registered = await users.register(
        _registration(phone_number="0924444444", public_user_id="PUB0005")
    )
    assert isinstance(registered, UserRegistered)
    await db_session.commit()

    created = await sessions.create(
        user_id=registered.user.id,
        token_hash="a" * 64,
        expires_at=datetime.now(UTC) + timedelta(days=1),
    )
    await db_session.commit()

    found = await sessions.get_valid_by_token_hash("a" * 64, now=datetime.now(UTC))
    assert found is not None
    assert found.session.id == created.id
    assert found.user.id == registered.user.id

    await sessions.revoke(created)
    await db_session.commit()

    revoked_lookup = await sessions.get_valid_by_token_hash("a" * 64, now=datetime.now(UTC))
    assert revoked_lookup is None


async def test_session_repository_rejects_expired_token(db_session: AsyncSession) -> None:
    """Treat an expired session token as invalid."""
    users = UserRepository(db_session)
    sessions = SessionRepository(db_session)

    registered = await users.register(
        _registration(phone_number="0925555555", public_user_id="PUB0006")
    )
    assert isinstance(registered, UserRegistered)
    await db_session.commit()

    await sessions.create(
        user_id=registered.user.id,
        token_hash="b" * 64,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    await db_session.commit()

    found = await sessions.get_valid_by_token_hash("b" * 64, now=datetime.now(UTC))
    assert found is None
