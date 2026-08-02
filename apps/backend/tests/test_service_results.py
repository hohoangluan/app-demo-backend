"""Public function result schema contract tests."""

from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from app.schemas.service_results import (
    ContactCallResult,
    EmergencyCallResult,
    MusicPlayResult,
    MusicStopResult,
    MusicVolumeResult,
    NavigationStartResult,
    NavigationStopResult,
    RideConfirmResult,
    RideQuoteResult,
)

RESULT_EXAMPLES: tuple[tuple[type[BaseModel], dict[str, Any]], ...] = (
    (
        RideQuoteResult,
        {
            "quote_id": "quote-123",
            "product_type": "standard",
            "price_estimate": {"currency": "VND", "amount": 85_000},
            "eta_minutes": 6,
            "expires_at": "2026-08-02T10:05:00Z",
        },
    ),
    (
        RideConfirmResult,
        {"quote_id": "quote-123", "ride_id": "ride-123", "ride_status": "requested"},
    ),
    (
        MusicPlayResult,
        {
            "track_id": "track-123",
            "title": "Nơi này có anh",
            "artist": "Sơn Tùng M-TP",
            "playback_state": "playing",
            "volume": 60,
        },
    ),
    (MusicStopResult, {"playback_state": "stopped"}),
    (MusicVolumeResult, {"volume_state": "changed", "level": 70}),
    (
        NavigationStartResult,
        {
            "navigation_id": "nav-123",
            "navigation_state": "navigating",
            "travel_mode": "walking",
            "destination": {
                "address": "Bưu điện Thành phố Hồ Chí Minh",
                "lat": 10.7798,
                "lng": 106.6990,
            },
        },
    ),
    (
        NavigationStopResult,
        {"navigation_id": "nav-123", "navigation_state": "stopped"},
    ),
    (
        EmergencyCallResult,
        {
            "emergency_state": "completed",
            "attempt": 1,
            "answered": True,
            "cycle": "stopped",
            "contact": "***789",
            "sms_sent": True,
        },
    ),
    (
        ContactCallResult,
        {
            "call_state": "calling",
            "contact_name": "Nguyễn Văn A",
            "phone_number": "***789",
        },
    ),
)


@pytest.mark.parametrize(("result_model", "payload"), RESULT_EXAMPLES)
def test_documented_result_example_validates(
    result_model: type[BaseModel],
    payload: dict[str, Any],
) -> None:
    """Validate one published result representation for each action."""
    result = result_model.model_validate(payload)

    assert result is not None


@pytest.mark.parametrize(
    "payload",
    [
        {"quote_id": "quote-123", "ride_id": None, "ride_status": "requested"},
        {"quote_id": "quote-123", "ride_id": "ride-123", "ride_status": "cancelled"},
    ],
)
def test_ride_confirm_result_rejects_ride_id_status_mismatch(
    payload: dict[str, object],
) -> None:
    """Match ride ID presence to requested and cancelled outcomes."""
    with pytest.raises(ValidationError):
        RideConfirmResult.model_validate(payload)


def test_ride_cancel_result_requires_null_ride_id() -> None:
    """Accept the published cancelled result with no ride ID."""
    result = RideConfirmResult.model_validate(
        {"quote_id": "quote-123", "ride_id": None, "ride_status": "cancelled"}
    )

    assert result.ride_id is None


@pytest.mark.parametrize(
    ("result_model", "payload"),
    [
        (MusicPlayResult, {"playback_state": "stopped"}),
        (MusicStopResult, {"playback_state": "playing"}),
        (MusicVolumeResult, {"volume_state": "unchanged"}),
        (NavigationStopResult, {"navigation_state": "navigating"}),
        (ContactCallResult, {"call_state": "completed"}),
    ],
)
def test_result_rejects_undocumented_fixed_state(
    result_model: type[BaseModel],
    payload: dict[str, str],
) -> None:
    """Reject endpoint-specific state values outside the published literal."""
    with pytest.raises(ValidationError):
        result_model.model_validate(payload)


def test_emergency_result_rejects_unknown_cycle() -> None:
    """Reject an emergency cycle outside the three published values."""
    with pytest.raises(ValidationError):
        EmergencyCallResult.model_validate(
            {
                "emergency_state": "completed",
                "attempt": 1,
                "answered": False,
                "cycle": "waiting",
                "contact": "***789",
                "sms_sent": True,
            }
        )


@pytest.mark.parametrize(("result_model", "payload"), RESULT_EXAMPLES)
def test_documented_result_rejects_unexpected_field(
    result_model: type[BaseModel],
    payload: dict[str, Any],
) -> None:
    """Reject an undocumented field on every published result shape."""
    with pytest.raises(ValidationError):
        result_model.model_validate(payload | {"unexpected_field": "nope"})


@pytest.mark.parametrize(
    ("field", "value"),
    [("lat", -90.1), ("lat", 90.1), ("lng", -180.1), ("lng", 180.1)],
)
def test_navigation_start_result_rejects_out_of_range_destination_coordinate(
    field: str,
    value: float,
) -> None:
    """Reject a navigation destination coordinate outside the shared bounds."""
    destination = {"address": "Destination", "lat": 10.0, "lng": 106.0} | {field: value}

    with pytest.raises(ValidationError):
        NavigationStartResult.model_validate(
            {
                "navigation_id": "nav-123",
                "navigation_state": "navigating",
                "travel_mode": "walking",
                "destination": destination,
            }
        )


@pytest.mark.parametrize(
    ("field", "value"),
    [("lat", -90), ("lat", 90), ("lng", -180), ("lng", 180)],
)
def test_navigation_start_result_accepts_destination_coordinate_boundaries(
    field: str,
    value: int,
) -> None:
    """Accept a navigation destination coordinate at the shared bounds."""
    destination = {"address": "Destination", "lat": 10.0, "lng": 106.0} | {field: value}

    result = NavigationStartResult.model_validate(
        {
            "navigation_id": "nav-123",
            "navigation_state": "navigating",
            "travel_mode": "walking",
            "destination": destination,
        }
    )

    assert getattr(result.destination, field) == value


def test_ride_quote_result_rejects_naive_expires_at() -> None:
    """Reject a naive expires_at datetime on the ride quote result."""
    with pytest.raises(ValidationError):
        RideQuoteResult.model_validate(
            {
                "quote_id": "quote-123",
                "product_type": "standard",
                "price_estimate": {"currency": "VND", "amount": 85_000},
                "eta_minutes": 6,
                "expires_at": "2026-08-02T10:05:00",
            }
        )


def test_ride_quote_result_normalizes_non_utc_expires_at() -> None:
    """Normalize a non-UTC expires_at offset to UTC on the ride quote result."""
    result = RideQuoteResult.model_validate(
        {
            "quote_id": "quote-123",
            "product_type": "standard",
            "price_estimate": {"currency": "VND", "amount": 85_000},
            "eta_minutes": 6,
            "expires_at": "2026-08-02T17:05:00+07:00",
        }
    )

    assert result.expires_at.isoformat() == "2026-08-02T10:05:00+00:00"


def test_ride_quote_result_accepts_utc_expires_at() -> None:
    """Accept an already-UTC expires_at datetime on the ride quote result."""
    result = RideQuoteResult.model_validate(
        {
            "quote_id": "quote-123",
            "product_type": "standard",
            "price_estimate": {"currency": "VND", "amount": 85_000},
            "eta_minutes": 6,
            "expires_at": "2026-08-02T10:05:00Z",
        }
    )

    assert result.expires_at.isoformat() == "2026-08-02T10:05:00+00:00"
