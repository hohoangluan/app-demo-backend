"""Allow call_answer and call_reject in operation/action constraints.

Revision ID: 20260822_0006
Revises: 20260822_0005
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260822_0006"
down_revision: str | None = "20260822_0005"
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
    "call_answer",
    "call_reject",
)


def _in_values(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


def upgrade() -> None:
    """Widen operation/action constraints for incoming-call controls."""
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
    """Remove incoming-call controls after callers stop creating them."""
    original_values = OPERATION_VALUES[:-2]
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
