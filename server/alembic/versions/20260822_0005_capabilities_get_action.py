"""Allow capabilities_get in operation/action CHECK constraints.

Revision ID: 20260822_0005
Revises: 20260803_0004
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260822_0005"
down_revision: str | None = "20260803_0004"
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
    "location_get",
    "capabilities_get",
)


def _in_values(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


def upgrade() -> None:
    """Widen operation/action constraints to allow capabilities_get."""
    op.drop_constraint("ck_operations_operation", "operations", type_="check")
    op.drop_constraint("ck_operations_action", "operations", type_="check")
    op.create_check_constraint(
        "ck_operations_operation",
        "operations",
        f"operation IN ({_in_values(OPERATION_VALUES)})",
    )
    op.create_check_constraint(
        "ck_operations_action",
        "operations",
        f"action IN ({_in_values(OPERATION_VALUES)})",
    )


def downgrade() -> None:
    """Remove capabilities_get after callers stop creating those operations."""
    original_values = OPERATION_VALUES[:-1]
    op.drop_constraint("ck_operations_operation", "operations", type_="check")
    op.drop_constraint("ck_operations_action", "operations", type_="check")
    op.create_check_constraint(
        "ck_operations_operation",
        "operations",
        f"operation IN ({_in_values(original_values)})",
    )
    op.create_check_constraint(
        "ck_operations_action",
        "operations",
        f"action IN ({_in_values(original_values)})",
    )
