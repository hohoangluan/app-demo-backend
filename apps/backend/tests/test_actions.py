"""Fixed endpoint mapping contract tests."""

import pytest

from app.actions import ACTION_ROUTE_MAP, ACTION_ROUTES, Action, HttpMethod, Operation

EXPECTED_ROUTES = (
    ("/api/v1/service/ride/quote", Operation.RIDE_QUOTE, Action.RIDE_QUOTE, 60),
    ("/api/v1/service/ride/confirm", Operation.RIDE_CONFIRM, Action.RIDE_CONFIRM, 90),
    ("/api/v1/service/music/play", Operation.MUSIC_PLAY, Action.MUSIC_PLAY, 45),
    ("/api/v1/service/music/stop", Operation.MUSIC_STOP, Action.MUSIC_STOP, 30),
    ("/api/v1/service/music/volume", Operation.MUSIC_VOLUME, Action.MUSIC_VOLUME, 30),
    (
        "/api/v1/service/navigation/start",
        Operation.NAVIGATION_START,
        Action.NAVIGATION_START,
        90,
    ),
    (
        "/api/v1/service/navigation/stop",
        Operation.NAVIGATION_STOP,
        Action.NAVIGATION_STOP,
        45,
    ),
    (
        "/api/v1/service/emergency/call",
        Operation.EMERGENCY_CALL,
        Action.EMERGENCY_CALL,
        21 * 60,
    ),
    ("/api/v1/service/contact/call", Operation.CONTACT_CALL, Action.CONTACT_CALL, 60),
    ("/api/v1/service/location/get", Operation.LOCATION_GET, Action.LOCATION_GET, 30),
    (
        "/api/v1/service/capabilities",
        Operation.CAPABILITIES_GET,
        Action.CAPABILITIES_GET,
        30,
    ),
    ("/api/v1/service/call/answer", Operation.CALL_ANSWER, Action.CALL_ANSWER, 20),
    ("/api/v1/service/call/reject", Operation.CALL_REJECT, Action.CALL_REJECT, 20),
)


@pytest.mark.parametrize(("path", "operation", "action", "timeout_seconds"), EXPECTED_ROUTES)
def test_public_route_maps_to_fixed_action_and_timeout(
    path: str,
    operation: Operation,
    action: Action,
    timeout_seconds: int,
) -> None:
    """Map every documented POST endpoint to exactly one fixed action."""
    route = ACTION_ROUTE_MAP[(HttpMethod.POST, path)]

    assert route.operation is operation
    assert route.action is action
    assert route.timeout_seconds == timeout_seconds


def test_mapping_covers_each_operation_and_action_once() -> None:
    """Keep every action and operation enum value covered one-to-one."""
    assert len(ACTION_ROUTES) == 13
    assert len(ACTION_ROUTE_MAP) == 13
    assert {route.operation for route in ACTION_ROUTES} == set(Operation)
    assert {route.action for route in ACTION_ROUTES} == set(Action)


def test_unknown_route_has_no_action_mapping() -> None:
    """Do not infer an action for an undocumented route."""
    assert ACTION_ROUTE_MAP.get((HttpMethod.POST, "/api/v1/service/unknown")) is None


def test_unknown_action_is_rejected_by_enum() -> None:
    """Reject action values outside the fixed allow-list."""
    with pytest.raises(ValueError, match="not a valid Action"):
        Action("unknown")
