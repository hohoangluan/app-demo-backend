"""Device delivery registration persistence model."""

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import CHAR, CheckConstraint, DateTime, Enum, Index, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func, text

from app.database import Base
from app.models.enums import DevicePlatform, DeviceStatus, enum_check_values, enum_values


class Device(Base):
    """Encrypted Android push-token registration."""

    __tablename__ = "devices"
    __table_args__ = (
        UniqueConstraint("device_id", name="uq_devices_device_id"),
        CheckConstraint(
            f"platform IN ({enum_check_values(DevicePlatform)})",
            name="ck_devices_platform",
        ),
        CheckConstraint(
            f"status IN ({enum_check_values(DeviceStatus)})",
            name="ck_devices_status",
        ),
        CheckConstraint(
            "length(push_token_fingerprint) = 64",
            name="ck_devices_push_token_fingerprint",
        ),
        Index("ix_devices_user_id", "user_id"),
        Index("ix_devices_push_token_fingerprint", "push_token_fingerprint"),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        default=uuid4,
    )
    user_id: Mapped[str] = mapped_column(String, nullable=False)
    device_id: Mapped[str] = mapped_column(String, nullable=False)
    platform: Mapped[DevicePlatform] = mapped_column(
        Enum(
            DevicePlatform,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=16,
        ),
        nullable=False,
    )
    push_token_ciphertext: Mapped[str] = mapped_column(Text, nullable=False)
    push_token_fingerprint: Mapped[str] = mapped_column(CHAR(64), nullable=False)
    status: Mapped[DeviceStatus] = mapped_column(
        Enum(
            DeviceStatus,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=16,
        ),
        nullable=False,
        default=DeviceStatus.ACTIVE,
        server_default=text("'active'"),
    )
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
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


Index(
    "ix_devices_resolver",
    Device.user_id,
    Device.status,
    Device.last_seen_at.desc(),
)
