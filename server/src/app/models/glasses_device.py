"""Glasses-to-user pairing persistence model.

Distinct from `Device` (`app/models/device.py`): `Device.device_id` is an
Android app-install identifier tied to an FCM push token. `GlassesDevice.
device_id` is the glasses' own hardware identifier, owned by an external
server ("kính"), which the Public Service API resolves to `user_id` for
routing -- an entirely separate identifier space.
"""

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import CheckConstraint, DateTime, Enum, Index, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func, text

from app.database import Base
from app.models.enums import GlassesLinkStatus, enum_check_values, enum_values


class GlassesDevice(Base):
    """Pairing between a glasses `device_id` and the owning app `user_id`."""

    __tablename__ = "glasses_devices"
    __table_args__ = (
        UniqueConstraint("device_id", name="uq_glasses_devices_device_id"),
        CheckConstraint(
            f"status IN ({enum_check_values(GlassesLinkStatus)})",
            name="ck_glasses_devices_status",
        ),
        Index("ix_glasses_devices_user_id", "user_id"),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        default=uuid4,
    )
    user_id: Mapped[str] = mapped_column(String, nullable=False)
    device_id: Mapped[str] = mapped_column(String, nullable=False)
    status: Mapped[GlassesLinkStatus] = mapped_column(
        Enum(
            GlassesLinkStatus,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=16,
        ),
        nullable=False,
        default=GlassesLinkStatus.ACTIVE,
        server_default=text("'active'"),
    )
    linked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
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
