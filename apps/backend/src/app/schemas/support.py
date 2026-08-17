"""Support ticket API schemas: submit feedback or a support request."""

from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.common import AwareUtcDatetime, TrimmedNonEmptyStr

SupportTicketCategory = Literal["feedback", "support_request"]


class SupportTicketRequest(BaseModel):
    """Submit a feedback message or a support request."""

    model_config = ConfigDict(extra="forbid")

    category: SupportTicketCategory
    message: Annotated[TrimmedNonEmptyStr, Field(max_length=2000)]


class SupportTicketData(BaseModel):
    """The submitted support ticket, acknowledged."""

    model_config = ConfigDict(extra="forbid")

    id: UUID
    category: SupportTicketCategory
    created_at: AwareUtcDatetime
