"""End-user account persistence model for the demo phone-app auth subsystem."""

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import Boolean, CheckConstraint, DateTime, Integer, String, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.database import Base

FONT_SIZE_OPTIONS = ("Nhỏ", "Vừa", "To")
VOICE_OPTIONS = ("Giọng Nữ", "Giọng Nam")
ANNOUNCE_CALLER_OPTIONS = ("name", "number_only", "ring_only")


class User(Base):
    """A registered end-user account (phone number + password)."""

    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("phone_number", name="uq_users_phone_number"),
        UniqueConstraint("public_user_id", name="uq_users_public_user_id"),
        CheckConstraint(
            "font_size_option IN ('Nhỏ', 'Vừa', 'To')",
            name="ck_users_font_size_option",
        ),
        CheckConstraint(
            "voice_option IN ('Giọng Nữ', 'Giọng Nam')",
            name="ck_users_voice_option",
        ),
        CheckConstraint(
            "announce_caller IN ('name', 'number_only', 'ring_only')",
            name="ck_users_announce_caller",
        ),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    # Short pairing code shown to the person and typed into the paired
    # Android device's own registration screen as its `user_id` -- the
    # internal UUID `id` above is never shown to a person.
    public_user_id: Mapped[str] = mapped_column(String, nullable=False)
    phone_number: Mapped[str] = mapped_column(String, nullable=False)
    display_name: Mapped[str | None] = mapped_column(String, nullable=True)
    password_hash: Mapped[str] = mapped_column(String, nullable=False)
    phone_verified: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    otp_hash: Mapped[str | None] = mapped_column(String, nullable=True)
    otp_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    otp_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    font_size_option: Mapped[str] = mapped_column(
        String, nullable=False, default="Vừa", server_default=text("'Vừa'")
    )
    voice_option: Mapped[str] = mapped_column(
        String, nullable=False, default="Giọng Nữ", server_default=text("'Giọng Nữ'")
    )
    high_contrast: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=text("false")
    )
    haptics_enabled: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default=text("true")
    )
    announce_caller: Mapped[str] = mapped_column(
        String, nullable=False, default="name", server_default=text("'name'")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
