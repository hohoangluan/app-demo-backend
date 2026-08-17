"""Session table persistence primitives: issue, resolve, and revoke."""

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.session import Session
from app.models.user import User


@dataclass(frozen=True, slots=True)
class AuthenticatedSession:
    """A valid (not revoked, not expired) session paired with its owning user."""

    session: Session
    user: User


class SessionRepository:
    """Persistence primitives for `sessions`: issue, resolve, and revoke.

    Issues statements against the caller-owned `AsyncSession` and never
    commits, mirroring `DeviceRepository`/`UserRepository`.
    """

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to a caller-owned session/transaction."""
        self._session = session

    async def create(self, *, user_id: UUID, token_hash: str, expires_at: datetime) -> Session:
        """Insert a new session row for `user_id`. Does not commit."""
        record = Session(user_id=user_id, token_hash=token_hash, expires_at=expires_at)
        self._session.add(record)
        await self._session.flush()
        return record

    async def get_valid_by_token_hash(
        self, token_hash: str, *, now: datetime
    ) -> AuthenticatedSession | None:
        """Return the not-revoked, not-expired session for `token_hash`, joined to its user."""
        result = await self._session.execute(
            select(Session, User)
            .join(User, User.id == Session.user_id)
            .where(
                Session.token_hash == token_hash,
                Session.revoked.is_(False),
                Session.expires_at > now,
            )
        )
        row = result.one_or_none()
        if row is None:
            return None
        session_record, user = row
        return AuthenticatedSession(session=session_record, user=user)

    async def revoke(self, record: Session) -> None:
        """Mark `record` revoked in place. Does not commit."""
        record.revoked = True
        await self._session.flush()

    async def revoke_all_for_user(self, user_id: UUID) -> None:
        """Revoke every not-already-revoked session owned by `user_id`. Does not commit."""
        await self._session.execute(
            update(Session)
            .where(Session.user_id == user_id, Session.revoked.is_(False))
            .values(revoked=True)
        )
