"""Fixed Public endpoint to operation/action mapping."""

from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Final


class HttpMethod(StrEnum):
    """HTTP methods used by function endpoints."""

    POST = "POST"


class Operation(StrEnum):
    """Operations exposed in Public API responses."""

    RIDE_QUOTE = "ride_quote"
    RIDE_CONFIRM = "ride_confirm"
    MUSIC_PLAY = "music_play"
    MUSIC_STOP = "music_stop"
    MUSIC_VOLUME = "music_volume"
    NAVIGATION_START = "navigation_start"
    NAVIGATION_STOP = "navigation_stop"
    EMERGENCY_CALL = "emergency_call"
    CONTACT_CALL = "contact_call"
    LOCATION_GET = "location_get"
    CAPABILITIES_GET = "capabilities_get"
    CALL_ANSWER = "call_answer"
    CALL_REJECT = "call_reject"


class Action(StrEnum):
    """Internal handler actions corresponding one-to-one with operations."""

    RIDE_QUOTE = "ride_quote"
    RIDE_CONFIRM = "ride_confirm"
    MUSIC_PLAY = "music_play"
    MUSIC_STOP = "music_stop"
    MUSIC_VOLUME = "music_volume"
    NAVIGATION_START = "navigation_start"
    NAVIGATION_STOP = "navigation_stop"
    EMERGENCY_CALL = "emergency_call"
    CONTACT_CALL = "contact_call"
    LOCATION_GET = "location_get"
    CAPABILITIES_GET = "capabilities_get"
    CALL_ANSWER = "call_answer"
    CALL_REJECT = "call_reject"


@dataclass(frozen=True, slots=True)
class ActionRoute:
    """Immutable mapping entry for a Public function endpoint."""

    method: HttpMethod
    path: str
    operation: Operation
    action: Action
    timeout_seconds: int


ACTION_ROUTES: Final = (
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/ride/quote",
        Operation.RIDE_QUOTE,
        Action.RIDE_QUOTE,
        60,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/ride/confirm",
        Operation.RIDE_CONFIRM,
        Action.RIDE_CONFIRM,
        90,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/music/play",
        Operation.MUSIC_PLAY,
        Action.MUSIC_PLAY,
        45,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/music/stop",
        Operation.MUSIC_STOP,
        Action.MUSIC_STOP,
        30,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/music/volume",
        Operation.MUSIC_VOLUME,
        Action.MUSIC_VOLUME,
        30,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/navigation/start",
        Operation.NAVIGATION_START,
        Action.NAVIGATION_START,
        90,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/navigation/stop",
        Operation.NAVIGATION_STOP,
        Action.NAVIGATION_STOP,
        45,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/emergency/call",
        Operation.EMERGENCY_CALL,
        Action.EMERGENCY_CALL,
        21 * 60,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/contact/call",
        Operation.CONTACT_CALL,
        Action.CONTACT_CALL,
        60,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/location/get",
        Operation.LOCATION_GET,
        Action.LOCATION_GET,
        30,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/capabilities",
        Operation.CAPABILITIES_GET,
        Action.CAPABILITIES_GET,
        30,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/call/answer",
        Operation.CALL_ANSWER,
        Action.CALL_ANSWER,
        20,
    ),
    ActionRoute(
        HttpMethod.POST,
        "/api/v1/service/call/reject",
        Operation.CALL_REJECT,
        Action.CALL_REJECT,
        20,
    ),
)

ACTION_ROUTE_MAP: Final = MappingProxyType(
    {(route.method, route.path): route for route in ACTION_ROUTES}
)
