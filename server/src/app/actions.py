"""Fixed mapping from Public function endpoints to phone actions.

Every Public endpoint maps to exactly one ``operation`` (the name the client
sees) and one ``action`` (the handler name the phone runs). They are equal
today, but stay separate types because they belong to different contracts.
"""

from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Final


class HttpMethod(StrEnum):
    """HTTP methods used by function endpoints."""

    POST = "POST"


class Operation(StrEnum):
    """Operation names exposed in Public API responses."""

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
    """Phone handler names, one per operation."""

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
    """One Public function endpoint and how long the phone has to report back."""

    method: HttpMethod
    path: str
    operation: Operation
    action: Action
    timeout_seconds: int


def _route(path: str, action: Action, timeout_seconds: int) -> ActionRoute:
    return ActionRoute(
        HttpMethod.POST,
        f"/api/v1/service/{path}",
        Operation(action.value),
        action,
        timeout_seconds,
    )


ACTION_ROUTES: Final = (
    _route("ride/quote", Action.RIDE_QUOTE, 60),
    _route("ride/confirm", Action.RIDE_CONFIRM, 90),
    _route("music/play", Action.MUSIC_PLAY, 45),
    _route("music/stop", Action.MUSIC_STOP, 30),
    _route("music/volume", Action.MUSIC_VOLUME, 30),
    _route("navigation/start", Action.NAVIGATION_START, 90),
    _route("navigation/stop", Action.NAVIGATION_STOP, 45),
    _route("emergency/call", Action.EMERGENCY_CALL, 21 * 60),
    _route("contact/call", Action.CONTACT_CALL, 60),
    _route("location/get", Action.LOCATION_GET, 30),
    _route("capabilities", Action.CAPABILITIES_GET, 30),
    _route("call/answer", Action.CALL_ANSWER, 20),
    _route("call/reject", Action.CALL_REJECT, 20),
)

ACTION_ROUTE_MAP: Final = MappingProxyType(
    {(route.method, route.path): route for route in ACTION_ROUTES}
)
ACTION_ROUTES_BY_ACTION: Final = MappingProxyType({route.action: route for route in ACTION_ROUTES})
