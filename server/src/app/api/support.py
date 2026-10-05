"""Support router: submit feedback or a support request as a logged-in user."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import UserPrincipal, require_user_session
from app.database import get_db_session
from app.repositories.support_ticket import SupportTicketRepository
from app.schemas.common import OkResponse
from app.schemas.support import SupportTicketData, SupportTicketRequest

router = APIRouter(prefix="/api/v1/support", tags=["support"])


@router.post("/tickets", response_model=OkResponse[SupportTicketData])
async def submit_support_ticket(
    body: SupportTicketRequest,
    principal: Annotated[UserPrincipal, Depends(require_user_session)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> OkResponse[SupportTicketData]:
    """Submit a feedback message or a support request for the logged-in caller."""
    repo = SupportTicketRepository(session)
    ticket = await repo.create(
        user_id=principal.user_id, category=body.category, message=body.message
    )
    if session is not None:
        await session.commit()

    data = SupportTicketData(
        id=ticket.id,
        category=ticket.category,  # type: ignore[arg-type]
        created_at=ticket.created_at,
    )
    return OkResponse(data=data)
