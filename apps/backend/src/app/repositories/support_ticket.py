"""Support ticket table persistence primitives: submit."""

from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.support_ticket import SupportTicket


class SupportTicketRepository:
    """Persistence primitives for `support_tickets`: submit.

    Issues statements against the caller-owned `AsyncSession` and never
    commits, mirroring the other repositories in this package.
    """

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to a caller-owned session/transaction."""
        self._session = session

    async def create(self, *, user_id: UUID, category: str, message: str) -> SupportTicket:
        """Insert a new support ticket row. Does not commit."""
        ticket = SupportTicket(user_id=user_id, category=category, message=message)
        self._session.add(ticket)
        await self._session.flush()
        return ticket
