"""Allow location_get in operations.operation/action CHECK constraints.

Revision ID: 20260803_0004
Revises: 20260803_0003
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260803_0004"
down_revision: str | None = "20260803_0003"
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
)


def _in_values(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


def upgrade() -> None:
    """Widen operation/action CHECK constraints to allow location_get."""
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
    """Narrow operation/action CHECK constraints back to the original nine values."""
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
