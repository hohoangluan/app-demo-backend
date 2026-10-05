"""Create devices and operations tables.

Revision ID: 20260802_0001
Revises: None
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "20260802_0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

OPERATION_VALUES = (
    "ride_quote",
    "ride_confirm",
    "music_play",
    "music_stop",
    "music_volume",
    "navigation_start",
    "navigation_stop",
    "emergency_call",
    "contact_call",
)


def _in_values(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


def upgrade() -> None:
    """Create the initial durable delivery schema."""
    op.create_table(
        "devices",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("device_id", sa.String(), nullable=False),
        sa.Column("platform", sa.String(length=16), nullable=False),
        sa.Column("push_token_ciphertext", sa.Text(), nullable=False),
        sa.Column("push_token_fingerprint", sa.CHAR(length=64), nullable=False),
        sa.Column(
            "status",
            sa.String(length=16),
            server_default=sa.text("'active'"),
            nullable=False,
        ),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("platform IN ('android')", name="ck_devices_platform"),
        sa.CheckConstraint(
            "status IN ('active', 'inactive', 'revoked')",
            name="ck_devices_status",
        ),
        sa.CheckConstraint(
            "length(push_token_fingerprint) = 64",
            name="ck_devices_push_token_fingerprint",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_devices"),
        sa.UniqueConstraint("device_id", name="uq_devices_device_id"),
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"], unique=False)
    op.create_index(
        "ix_devices_push_token_fingerprint",
        "devices",
        ["push_token_fingerprint"],
        unique=False,
    )
    op.create_index(
        "ix_devices_resolver",
        "devices",
        ["user_id", "status", sa.text("last_seen_at DESC")],
        unique=False,
    )

    op.create_table(
        "operations",
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_id", sa.String(), nullable=False),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("operation", sa.String(length=32), nullable=False),
        sa.Column("action", sa.String(length=32), nullable=False),
        sa.Column("params", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("request_fingerprint", sa.CHAR(length=64), nullable=False),
        sa.Column(
            "request_state",
            sa.String(length=16),
            server_default=sa.text("'processing'"),
            nullable=False,
        ),
        sa.Column(
            "result",
            postgresql.JSONB(astext_type=sa.Text(), none_as_null=True),
            nullable=True,
        ),
        sa.Column(
            "error",
            postgresql.JSONB(astext_type=sa.Text(), none_as_null=True),
            nullable=True,
        ),
        sa.Column("device_id", sa.String(), nullable=True),
        sa.Column(
            "delivery_state",
            sa.String(length=24),
            server_default=sa.text("'received'"),
            nullable=False,
        ),
        sa.Column(
            "delivery_attempts",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column("next_delivery_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("delivery_locked_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("provider_message_id", sa.String(), nullable=True),
        sa.Column("callback_state", sa.String(length=24), nullable=False),
        sa.Column(
            "callback_attempts",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column("next_callback_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("callback_locked_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("report_payload_hash", sa.CHAR(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            f"operation IN ({_in_values(OPERATION_VALUES)})",
            name="ck_operations_operation",
        ),
        sa.CheckConstraint(
            f"action IN ({_in_values(OPERATION_VALUES)})",
            name="ck_operations_action",
        ),
        sa.CheckConstraint("operation = action", name="ck_operations_mapping"),
        sa.CheckConstraint(
            "request_state IN ('processing', 'succeeded', 'failed', 'timed_out')",
            name="ck_operations_request_state",
        ),
        sa.CheckConstraint(
            "delivery_state IN "
            "('received', 'sending', 'retry', 'sent', 'failed', "
            "'report_received', 'report_timeout')",
            name="ck_operations_delivery_state",
        ),
        sa.CheckConstraint(
            "callback_state IN "
            "('not_required', 'pending', 'sending', 'retry', 'delivered', 'dead_letter')",
            name="ck_operations_callback_state",
        ),
        sa.CheckConstraint(
            "delivery_attempts >= 0",
            name="ck_operations_delivery_attempts",
        ),
        sa.CheckConstraint(
            "callback_attempts >= 0",
            name="ck_operations_callback_attempts",
        ),
        sa.CheckConstraint(
            "length(request_fingerprint) = 64",
            name="ck_operations_request_fingerprint",
        ),
        sa.CheckConstraint(
            "report_payload_hash IS NULL OR length(report_payload_hash) = 64",
            name="ck_operations_report_payload_hash",
        ),
        sa.CheckConstraint(
            "(request_state = 'processing' AND result IS NULL AND error IS NULL "
            "AND completed_at IS NULL) OR "
            "(request_state = 'succeeded' AND result IS NOT NULL AND error IS NULL "
            "AND completed_at IS NOT NULL) OR "
            "(request_state IN ('failed', 'timed_out') AND result IS NULL "
            "AND error IS NOT NULL AND completed_at IS NOT NULL)",
            name="ck_operations_terminal_payload",
        ),
        sa.PrimaryKeyConstraint("request_id", name="pk_operations"),
    )
    op.create_index(
        "ix_operations_delivery_due",
        "operations",
        ["request_state", "delivery_state", "next_delivery_at"],
        unique=False,
    )
    op.create_index(
        "ix_operations_timeout_due",
        "operations",
        ["request_state", "expires_at"],
        unique=False,
    )
    op.create_index(
        "ix_operations_callback_due",
        "operations",
        ["callback_state", "next_callback_at"],
        unique=False,
    )
    op.create_index(
        "ix_operations_client_created",
        "operations",
        ["client_id", sa.text("created_at DESC")],
        unique=False,
    )


def downgrade() -> None:
    """Drop operations before devices."""
    op.drop_table("operations")
    op.drop_table("devices")
