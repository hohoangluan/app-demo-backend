"""Add accessibility preference columns to users and create support_tickets.

Revision ID: 20260803_0002
Revises: 20260803_0001
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "20260803_0002"
down_revision: str | None = "20260803_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add users preference columns and create support_tickets."""
    op.add_column(
        "users",
        sa.Column(
            "font_size_option",
            sa.String(),
            nullable=False,
            server_default=sa.text("'Vừa'"),
        ),
    )
    op.add_column(
        "users",
        sa.Column(
            "voice_option",
            sa.String(),
            nullable=False,
            server_default=sa.text("'Giọng Nữ'"),
        ),
    )
    op.add_column(
        "users",
        sa.Column(
            "high_contrast",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
    )
    op.add_column(
        "users",
        sa.Column(
            "haptics_enabled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("true"),
        ),
    )
    op.create_check_constraint(
        "ck_users_font_size_option",
        "users",
        "font_size_option IN ('Nhỏ', 'Vừa', 'To')",
    )
    op.create_check_constraint(
        "ck_users_voice_option",
        "users",
        "voice_option IN ('Giọng Nữ', 'Giọng Nam')",
    )

    op.create_table(
        "support_tickets",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("category", sa.String(length=20), nullable=False),
        sa.Column("message", sa.Text(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "category IN ('feedback', 'support_request')",
            name="ck_support_tickets_category",
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_support_tickets_user_id_users"),
        sa.PrimaryKeyConstraint("id", name="pk_support_tickets"),
    )
    op.create_index("ix_support_tickets_user_id", "support_tickets", ["user_id"], unique=False)


def downgrade() -> None:
    """Drop support_tickets and users preference columns."""
    op.drop_table("support_tickets")
    op.drop_constraint("ck_users_voice_option", "users", type_="check")
    op.drop_constraint("ck_users_font_size_option", "users", type_="check")
    op.drop_column("users", "haptics_enabled")
    op.drop_column("users", "high_contrast")
    op.drop_column("users", "voice_option")
    op.drop_column("users", "font_size_option")
