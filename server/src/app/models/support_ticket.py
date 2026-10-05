"""Support ticket persistence model: feedback and support requests from end users."""

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, String, Text
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.database import Base

SUPPORT_TICKET_CATEGORIES = ("feedback", "support_request")


class SupportTicket(Base):
    """A feedback or support-request message submitted by a logged-in user."""

    __tablename__ = "support_tickets"
    __table_args__ = (
        CheckConstraint(
            "category IN ('feedback', 'support_request')",
            name="ck_support_tickets_category",
        ),
        Index("ix_support_tickets_user_id", "user_id"),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    category: Mapped[str] = mapped_column(String(20), nullable=False)
    message: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
