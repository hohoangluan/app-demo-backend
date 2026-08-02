"""Durable operation and delivery lifecycle persistence model."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import CHAR, CheckConstraint, DateTime, Enum, Index, Integer, String, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.actions import Action
from app.actions import Operation as OperationName
from app.database import Base
from app.models.enums import (
    CallbackState,
    DeliveryState,
    RequestState,
    enum_check_values,
    enum_values,
)

type JsonObject = dict[str, object]


class Operation(Base):
    """One idempotent Public request and its internal delivery state."""

    __tablename__ = "operations"
    __table_args__ = (
        CheckConstraint(
            f"operation IN ({enum_check_values(OperationName)})",
            name="ck_operations_operation",
        ),
        CheckConstraint(
            f"action IN ({enum_check_values(Action)})",
            name="ck_operations_action",
        ),
        CheckConstraint("operation = action", name="ck_operations_mapping"),
        CheckConstraint(
            f"request_state IN ({enum_check_values(RequestState)})",
            name="ck_operations_request_state",
        ),
        CheckConstraint(
            f"delivery_state IN ({enum_check_values(DeliveryState)})",
            name="ck_operations_delivery_state",
        ),
        CheckConstraint(
            f"callback_state IN ({enum_check_values(CallbackState)})",
            name="ck_operations_callback_state",
        ),
        CheckConstraint("delivery_attempts >= 0", name="ck_operations_delivery_attempts"),
        CheckConstraint("callback_attempts >= 0", name="ck_operations_callback_attempts"),
        CheckConstraint(
            "length(request_fingerprint) = 64",
            name="ck_operations_request_fingerprint",
        ),
        CheckConstraint(
            "report_payload_hash IS NULL OR length(report_payload_hash) = 64",
            name="ck_operations_report_payload_hash",
        ),
        CheckConstraint(
            "("
            "request_state = 'processing' AND result IS NULL AND error IS NULL "
            "AND completed_at IS NULL"
            ") OR ("
            "request_state = 'succeeded' AND result IS NOT NULL AND error IS NULL "
            "AND completed_at IS NOT NULL"
            ") OR ("
            "request_state IN ('failed', 'timed_out') AND result IS NULL AND error IS NOT NULL "
            "AND completed_at IS NOT NULL"
            ")",
            name="ck_operations_terminal_payload",
        ),
        Index(
            "ix_operations_delivery_due",
            "request_state",
            "delivery_state",
            "next_delivery_at",
        ),
        Index("ix_operations_timeout_due", "request_state", "expires_at"),
        Index("ix_operations_callback_due", "callback_state", "next_callback_at"),
    )

    request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
    )
    client_id: Mapped[str] = mapped_column(String, nullable=False)
    user_id: Mapped[str] = mapped_column(String, nullable=False)
    operation: Mapped[OperationName] = mapped_column(
        Enum(
            OperationName,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=32,
        ),
        nullable=False,
    )
    action: Mapped[Action] = mapped_column(
        Enum(
            Action,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=32,
        ),
        nullable=False,
    )
    params: Mapped[JsonObject] = mapped_column(JSONB, nullable=False)
    request_fingerprint: Mapped[str] = mapped_column(CHAR(64), nullable=False)
    request_state: Mapped[RequestState] = mapped_column(
        Enum(
            RequestState,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=16,
        ),
        nullable=False,
        default=RequestState.PROCESSING,
        server_default=text("'processing'"),
    )
    result: Mapped[JsonObject | None] = mapped_column(
        JSONB(none_as_null=True),
        nullable=True,
    )
    error: Mapped[JsonObject | None] = mapped_column(
        JSONB(none_as_null=True),
        nullable=True,
    )
    device_id: Mapped[str | None] = mapped_column(String, nullable=True)
    delivery_state: Mapped[DeliveryState] = mapped_column(
        Enum(
            DeliveryState,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=24,
        ),
        nullable=False,
        default=DeliveryState.RECEIVED,
        server_default=text("'received'"),
    )
    delivery_attempts: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
    next_delivery_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    delivery_locked_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    provider_message_id: Mapped[str | None] = mapped_column(String, nullable=True)
    callback_state: Mapped[CallbackState] = mapped_column(
        Enum(
            CallbackState,
            native_enum=False,
            create_constraint=False,
            values_callable=enum_values,
            length=24,
        ),
        nullable=False,
    )
    callback_attempts: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
    next_callback_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    callback_locked_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    report_payload_hash: Mapped[str | None] = mapped_column(CHAR(64), nullable=True)
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
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )


Index(
    "ix_operations_client_created",
    Operation.client_id,
    Operation.created_at.desc(),
)
