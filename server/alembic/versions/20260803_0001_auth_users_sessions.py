"""Create users and sessions tables for the demo phone-app auth subsystem.

Revision ID: 20260803_0001
Revises: 20260802_0001
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "20260803_0001"
down_revision: str | None = "20260802_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the users and sessions tables."""
    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("public_user_id", sa.String(), nullable=False),
        sa.Column("phone_number", sa.String(), nullable=False),
        sa.Column("display_name", sa.String(), nullable=True),
        sa.Column("password_hash", sa.String(), nullable=False),
        sa.Column(
            "phone_verified",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
        sa.Column("otp_hash", sa.String(), nullable=True),
        sa.Column("otp_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "otp_attempts",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
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
        sa.PrimaryKeyConstraint("id", name="pk_users"),
        sa.UniqueConstraint("phone_number", name="uq_users_phone_number"),
        sa.UniqueConstraint("public_user_id", name="uq_users_public_user_id"),
    )

    op.create_table(
        "sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("token_hash", sa.CHAR(length=64), nullable=False),
        sa.Column(
            "revoked",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_sessions_user_id_users"),
        sa.PrimaryKeyConstraint("id", name="pk_sessions"),
        sa.UniqueConstraint("token_hash", name="uq_sessions_token_hash"),
    )
    op.create_index("ix_sessions_user_id", "sessions", ["user_id"], unique=False)


def downgrade() -> None:
    """Drop sessions before users."""
    op.drop_table("sessions")
    op.drop_table("users")
