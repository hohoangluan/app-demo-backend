"""Create glasses_devices table.

Revision ID: 20260803_0003
Revises: 20260803_0002
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "20260803_0003"
down_revision: str | None = "20260803_0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the glasses_devices pairing table."""
    op.create_table(
        "glasses_devices",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("device_id", sa.String(), nullable=False),
        sa.Column(
            "status",
            sa.String(length=16),
            server_default=sa.text("'active'"),
            nullable=False,
        ),
        sa.Column("linked_at", sa.DateTime(timezone=True), nullable=False),
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
        sa.CheckConstraint(
            "status IN ('active', 'inactive')",
            name="ck_glasses_devices_status",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_glasses_devices"),
        sa.UniqueConstraint("device_id", name="uq_glasses_devices_device_id"),
    )
    op.create_index("ix_glasses_devices_user_id", "glasses_devices", ["user_id"], unique=False)


def downgrade() -> None:
    """Drop the glasses_devices pairing table."""
    op.drop_table("glasses_devices")
