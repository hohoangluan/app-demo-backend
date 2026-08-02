"""Public function request schema contract tests."""

from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from app.schemas.service_requests import (
    ContactCallRequest,
    EmergencyCallRequest,
    MusicPlayRequest,
    MusicStopRequest,
    MusicVolumeRequest,
    NavigationStartRequest,
    NavigationStopRequest,
    RideConfirmRequest,
    RideQuoteRequest,
)

BASE_REQUEST: dict[str, Any] = {
    "user_id": "user-123",
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
}

REQUEST_EXAMPLES: tuple[tuple[type[BaseModel], dict[str, Any]], ...] = (
    (
        RideQuoteRequest,
        BASE_REQUEST
        | {
            "current_location": {"lat": 10.7769, "lng": 106.7009},
            "destination": {
                "address": "Đại học Bách Khoa TP.HCM",
                "lat": 10.7721,
                "lng": 106.6578,
            },
        },
    ),
    (RideConfirmRequest, BASE_REQUEST | {"quote_id": "quote-123", "confirm": True}),
    (MusicPlayRequest, BASE_REQUEST | {"song": "Nơi này có anh", "volume": 60}),
    (MusicStopRequest, BASE_REQUEST),
    (MusicVolumeRequest, BASE_REQUEST | {"direction": "up"}),
    (
        NavigationStartRequest,
        BASE_REQUEST | {"destination": {"address": "Bưu điện Thành phố Hồ Chí Minh"}},
    ),
    (NavigationStopRequest, BASE_REQUEST | {"navigation_id": "nav-123"}),
    (EmergencyCallRequest, BASE_REQUEST),
    (ContactCallRequest, BASE_REQUEST | {"name": "Nguyễn Văn A"}),
)


@pytest.mark.parametrize(("request_model", "payload"), REQUEST_EXAMPLES)
def test_documented_request_example_validates(
    request_model: type[BaseModel],
    payload: dict[str, Any],
) -> None:
    """Validate one published request representation for each action."""
    request = request_model.model_validate(payload)

    assert request.model_dump()["request_id"] is not None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("lat", -90),
        ("lat", 90),
        ("lng", -180),
        ("lng", 180),
    ],
)
def test_current_location_accepts_coordinate_boundaries(field: str, value: int) -> None:
    """Accept documented current-location coordinate boundaries."""
    location = {"lat": 10.0, "lng": 106.0, field: value}
    request = RideQuoteRequest.model_validate(
        BASE_REQUEST
        | {
            "current_location": location,
            "destination": {"address": "Destination"},
        }
    )

    assert getattr(request.current_location, field) == value


@pytest.mark.parametrize(
    ("field", "value"),
    [("lat", -90.1), ("lat", 90.1), ("lng", -180.1), ("lng", 180.1)],
)
def test_current_location_rejects_out_of_range_coordinate(field: str, value: float) -> None:
    """Reject current-location coordinates outside documented ranges."""
    location = {"lat": 10.0, "lng": 106.0, field: value}

    with pytest.raises(ValidationError):
        RideQuoteRequest.model_validate(
            BASE_REQUEST
            | {
                "current_location": location,
                "destination": {"address": "Destination"},
            }
        )


@pytest.mark.parametrize(
    "destination",
    [
        {"address": "Bưu điện Thành phố Hồ Chí Minh"},
        {"lat": 10.7798, "lng": 106.6990},
        {"address": "Bưu điện Thành phố Hồ Chí Minh", "lat": 10.7798, "lng": 106.6990},
    ],
)
def test_destination_accepts_documented_shapes(destination: dict[str, object]) -> None:
    """Accept address, coordinate-pair, and combined destination shapes."""
    request = NavigationStartRequest.model_validate(BASE_REQUEST | {"destination": destination})

    assert request.destination is not None


@pytest.mark.parametrize("destination", [{}, {"lat": 10.7798}, {"lng": 106.6990}])
def test_destination_rejects_missing_or_partial_location(
    destination: dict[str, float],
) -> None:
    """Reject a destination without an address or complete coordinate pair."""
    with pytest.raises(ValidationError):
        NavigationStartRequest.model_validate(BASE_REQUEST | {"destination": destination})


@pytest.mark.parametrize("volume", [0, 100])
def test_music_play_accepts_volume_boundaries(volume: int) -> None:
    """Accept documented initial-volume boundaries."""
    request = MusicPlayRequest.model_validate(BASE_REQUEST | {"song": "Song", "volume": volume})

    assert request.volume == volume


@pytest.mark.parametrize("volume", [-1, 101])
def test_music_play_rejects_out_of_range_volume(volume: int) -> None:
    """Reject initial volume outside zero through one hundred."""
    with pytest.raises(ValidationError):
        MusicPlayRequest.model_validate(BASE_REQUEST | {"song": "Song", "volume": volume})


@pytest.mark.parametrize("level", [0, 100])
def test_music_volume_accepts_absolute_level_boundaries(level: int) -> None:
    """Accept documented absolute-volume boundaries."""
    request = MusicVolumeRequest.model_validate(BASE_REQUEST | {"level": level})

    assert request.level == level


@pytest.mark.parametrize("level", [-1, 101])
def test_music_volume_rejects_out_of_range_level(level: int) -> None:
    """Reject an absolute-volume level outside zero through one hundred."""
    with pytest.raises(ValidationError):
        MusicVolumeRequest.model_validate(BASE_REQUEST | {"level": level})


@pytest.mark.parametrize(
    "volume_control",
    [{}, {"direction": "up", "level": 70}],
)
def test_music_volume_requires_exactly_one_control(
    volume_control: dict[str, object],
) -> None:
    """Reject missing or conflicting relative/absolute controls."""
    with pytest.raises(ValidationError, match="exactly one"):
        MusicVolumeRequest.model_validate(BASE_REQUEST | volume_control)


def test_music_volume_rejects_unknown_direction() -> None:
    """Reject a relative-volume direction outside up and down."""
    with pytest.raises(ValidationError):
        MusicVolumeRequest.model_validate(BASE_REQUEST | {"direction": "sideways"})


def test_contact_call_rejects_empty_name() -> None:
    """Reject the explicitly forbidden empty contact name."""
    with pytest.raises(ValidationError):
        ContactCallRequest.model_validate(BASE_REQUEST | {"name": ""})


@pytest.mark.parametrize(("request_model", "payload"), REQUEST_EXAMPLES)
def test_documented_request_rejects_unexpected_field(
    request_model: type[BaseModel],
    payload: dict[str, Any],
) -> None:
    """Reject an undocumented field on every published request shape."""
    with pytest.raises(ValidationError):
        request_model.model_validate(payload | {"unexpected_field": "nope"})


@pytest.mark.parametrize(
    ("request_model", "field", "extra_payload"),
    [
        (RideConfirmRequest, "quote_id", {"confirm": True}),
        (MusicPlayRequest, "song", {}),
        (NavigationStopRequest, "navigation_id", {}),
        (ContactCallRequest, "name", {}),
    ],
)
def test_client_supplied_field_rejects_whitespace_only_value(
    request_model: type[BaseModel],
    field: str,
    extra_payload: dict[str, Any],
) -> None:
    """Reject client-supplied free-text fields containing only whitespace."""
    payload = BASE_REQUEST | extra_payload | {field: "   "}

    with pytest.raises(ValidationError):
        request_model.model_validate(payload)


@pytest.mark.parametrize(
    ("request_model", "field", "extra_payload"),
    [
        (RideConfirmRequest, "quote_id", {"confirm": True}),
        (MusicPlayRequest, "song", {}),
        (NavigationStopRequest, "navigation_id", {}),
        (ContactCallRequest, "name", {}),
    ],
)
def test_client_supplied_field_trims_whitespace(
    request_model: type[BaseModel],
    field: str,
    extra_payload: dict[str, Any],
) -> None:
    """Store the trimmed value for client-supplied free-text request fields."""
    payload = BASE_REQUEST | extra_payload | {field: f"  {field}-value  "}

    request = request_model.model_validate(payload)

    assert getattr(request, field) == f"{field}-value"


def test_service_request_user_id_trims_whitespace() -> None:
    """Store the trimmed user_id shared by every Public function request."""
    request = MusicStopRequest.model_validate(BASE_REQUEST | {"user_id": "  user-123  "})

    assert request.user_id == "user-123"


def test_service_request_rejects_whitespace_only_user_id() -> None:
    """Reject a whitespace-only user_id shared by every Public function request."""
    with pytest.raises(ValidationError):
        MusicStopRequest.model_validate(BASE_REQUEST | {"user_id": "   "})


@pytest.mark.parametrize(
    ("field", "value"),
    [("lat", -90.1), ("lat", 90.1), ("lng", -180.1), ("lng", 180.1)],
)
def test_destination_rejects_out_of_range_coordinate(field: str, value: float) -> None:
    """Reject destination coordinates outside the shared coordinate bounds."""
    destination = {"lat": 10.0, "lng": 106.0} | {field: value}

    with pytest.raises(ValidationError):
        NavigationStartRequest.model_validate(BASE_REQUEST | {"destination": destination})


@pytest.mark.parametrize(
    ("field", "value"),
    [("lat", -90), ("lat", 90), ("lng", -180), ("lng", 180)],
)
def test_destination_accepts_coordinate_boundaries(field: str, value: int) -> None:
    """Accept destination coordinates at the shared coordinate bounds."""
    destination = {"lat": 10.0, "lng": 106.0} | {field: value}

    request = NavigationStartRequest.model_validate(BASE_REQUEST | {"destination": destination})

    assert getattr(request.destination, field) == value
