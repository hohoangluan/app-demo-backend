"""Add caller-announcement privacy preference.

Revision ID: 20260822_0007
Revises: 20260822_0006
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260822_0007"
down_revision: str | None = "20260822_0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column(
            "announce_caller",
            sa.String(),
            nullable=False,
            server_default=sa.text("'name'"),
        ),
    )
    op.create_check_constraint(
        "ck_users_announce_caller",
        "users",
        "announce_caller IN ('name', 'number_only', 'ring_only')",
    )


def downgrade() -> None:
    op.drop_constraint("ck_users_announce_caller", "users", type_="check")
    op.drop_column("users", "announce_caller")
