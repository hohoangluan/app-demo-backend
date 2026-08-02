"""Database logical enum values persisted as checked varchar columns."""

from enum import StrEnum

from app.actions import Action, Operation


class DevicePlatform(StrEnum):
    """Supported device platforms."""

    ANDROID = "android"


class DeviceStatus(StrEnum):
    """Device enrollment and delivery states."""

    ACTIVE = "active"
    INACTIVE = "inactive"
    REVOKED = "revoked"


class RequestState(StrEnum):
    """Public operation states persisted in PostgreSQL."""

    PROCESSING = "processing"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    TIMED_OUT = "timed_out"


class DeliveryState(StrEnum):
    """Internal command delivery states."""

    RECEIVED = "received"
    SENDING = "sending"
    RETRY = "retry"
    SENT = "sent"
    FAILED = "failed"
    REPORT_RECEIVED = "report_received"
    REPORT_TIMEOUT = "report_timeout"


class CallbackState(StrEnum):
    """Terminal-result callback delivery states."""

    NOT_REQUIRED = "not_required"
    PENDING = "pending"
    SENDING = "sending"
    RETRY = "retry"
    DELIVERED = "delivered"
    DEAD_LETTER = "dead_letter"


def enum_values[EnumT: StrEnum](enum_type: type[EnumT]) -> list[str]:
    """Return values so SQLAlchemy persists StrEnum values instead of names."""
    return [member.value for member in enum_type]


def enum_check_values[EnumT: StrEnum](enum_type: type[EnumT]) -> str:
    """Render deterministic SQL literals for a named check constraint."""
    return ", ".join(f"'{member.value}'" for member in enum_type)


__all__ = [
    "Action",
    "CallbackState",
    "DeliveryState",
    "DevicePlatform",
    "DeviceStatus",
    "Operation",
    "RequestState",
]
