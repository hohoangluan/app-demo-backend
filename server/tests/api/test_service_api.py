"""HTTP tests for the Public function endpoints with a fake operation service."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any
from uuid import UUID, uuid4

import pytest
from fastapi import status
from httpx import ASGITransport, AsyncClient

from app.actions import ACTION_ROUTES, Action, ActionRoute
from app.api.service import get_operation_service
from app.application import create_app
from app.errors import GlassesDeviceNotLinkedError, PublicApiError, RequestIdConflictError
from app.models.enums import CallbackState, DeliveryState, RequestState
from app.models.operation import Operation
from tests.helpers import PUBLIC_TOKEN

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

    from fastapi import FastAPI

    from app.config import Settings
    from app.schemas.service_requests import ServiceRequest

_BODIES: dict[Action, dict[str, Any]] = {
    Action.RIDE_QUOTE: {
        "current_location": {"lat": 10.77, "lng": 106.70},
        "destination": {"address": "Bến Thành"},
    },
    Action.RIDE_CONFIRM: {"quote_id": "quote-1", "confirm": True},
    Action.MUSIC_PLAY: {"song": "Nơi này có anh"},
    Action.MUSIC_STOP: {},
    Action.MUSIC_VOLUME: {"level": 70},
    Action.NAVIGATION_START: {"destination": {"address": "Bưu điện Thành phố"}},
    Action.NAVIGATION_STOP: {"navigation_id": "nav-1"},
    Action.EMERGENCY_CALL: {},
    Action.CONTACT_CALL: {"name": "Mẹ"},
    Action.LOCATION_GET: {},
    Action.CAPABILITIES_GET: {},
    Action.CALL_ANSWER: {},
    Action.CALL_REJECT: {},
}
_AUTH = {"Authorization": f"Bearer {PUBLIC_TOKEN}"}


class FakeOperationService:
    """Records submissions and returns a processing operation, or raises ``error``."""

    def __init__(self, error: PublicApiError | None = None) -> None:
        """Start with no recorded calls; raise ``error`` from every submit when set."""
        self.error = error
        self.calls: list[tuple[str, Action, ServiceRequest]] = []

    async def submit(self, *, client_id: str, action: Action, request: ServiceRequest) -> Operation:
        """Record the call, then raise the configured error or return a new operation."""
        self.calls.append((client_id, action, request))
        if self.error is not None:
            raise self.error
        now = datetime.now(UTC)
        return Operation(
            request_id=request.request_id,
            client_id=client_id,
            user_id="user-1",
            operation=action.value,
            action=action,
            params={},
            request_fingerprint="b" * 64,
            request_state=RequestState.PROCESSING,
            delivery_state=DeliveryState.RECEIVED,
            callback_state=CallbackState.NOT_REQUIRED,
            created_at=now,
            updated_at=now,
            expires_at=now,
        )


@pytest.fixture
def fake_service() -> FakeOperationService:
    """Return a fresh fake service for one test."""
    return FakeOperationService()


@pytest.fixture
def app(settings: Settings, fake_service: FakeOperationService) -> FastAPI:
    """Create the app with the operation service replaced by the fake."""
    application = create_app(settings)
    application.dependency_overrides[get_operation_service] = lambda: fake_service
    return application


@pytest.fixture
async def http(app: FastAPI) -> AsyncIterator[AsyncClient]:
    """Yield an HTTP client bound to the app."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


def _body(route: ActionRoute, request_id: UUID | None = None) -> dict[str, Any]:
    return {
        "device_id": "glasses-1",
        "request_id": str(request_id or uuid4()),
        **_BODIES[route.action],
    }


def test_every_route_has_a_sample_body() -> None:
    """Every action has a sample body, so the parametrized tests cover all of them."""
    assert set(_BODIES) == {route.action for route in ACTION_ROUTES} == set(Action)


@pytest.mark.parametrize("route", ACTION_ROUTES, ids=lambda route: route.action.value)
async def test_missing_bearer_token_returns_401(
    http: AsyncClient, fake_service: FakeOperationService, route: ActionRoute
) -> None:
    """Unauthenticated calls are rejected before anything is submitted."""
    response = await http.post(route.path, json=_body(route))

    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"detail": "UNAUTHORIZED"}
    assert fake_service.calls == []


@pytest.mark.parametrize("route", ACTION_ROUTES, ids=lambda route: route.action.value)
async def test_valid_request_is_accepted_with_its_fixed_action(
    http: AsyncClient, fake_service: FakeOperationService, settings: Settings, route: ActionRoute
) -> None:
    """Each endpoint submits its own fixed action and returns the 202 envelope."""
    request_id = uuid4()

    response = await http.post(route.path, json=_body(route, request_id), headers=_AUTH)

    assert response.status_code == status.HTTP_202_ACCEPTED
    data = response.json()["data"]
    assert response.json()["status"] == "ok"
    assert data["request_id"] == str(request_id)
    assert data["operation"] == route.operation.value
    assert data["request_state"] == "processing"
    assert data["status_url"] == f"/api/v1/requests/{request_id}"
    [(client_id, action, request)] = fake_service.calls
    assert client_id == settings.public_api_client_id
    assert action is route.action
    assert request.device_id == "glasses-1"


@pytest.mark.parametrize(
    "payload",
    [
        {"direction": "up", "level": 50},
        {"level": 150},
        {},
        {"level": 50, "unexpected": True},
    ],
    ids=["direction-and-level", "level-out-of-range", "neither", "extra-field"],
)
async def test_invalid_volume_body_returns_400_without_submitting(
    http: AsyncClient, fake_service: FakeOperationService, payload: dict[str, Any]
) -> None:
    """Invalid bodies map to 400 INVALID_REQUEST and never reach the service."""
    body = {"device_id": "glasses-1", "request_id": str(uuid4()), **payload}

    response = await http.post("/api/v1/service/music/volume", json=body, headers=_AUTH)

    assert response.status_code == status.HTTP_400_BAD_REQUEST
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert fake_service.calls == []


@pytest.mark.parametrize(
    ("error", "status_code", "code"),
    [
        (RequestIdConflictError("conflict"), status.HTTP_409_CONFLICT, "REQUEST_ID_CONFLICT"),
        (
            GlassesDeviceNotLinkedError("not linked"),
            status.HTTP_404_NOT_FOUND,
            "GLASSES_DEVICE_NOT_LINKED",
        ),
    ],
)
async def test_service_errors_use_the_public_error_envelope(
    http: AsyncClient,
    fake_service: FakeOperationService,
    error: PublicApiError,
    status_code: int,
    code: str,
) -> None:
    """Domain errors from the service are rendered with the Public error envelope."""
    fake_service.error = error
    route = ACTION_ROUTES[0]

    response = await http.post(route.path, json=_body(route), headers=_AUTH)

    assert response.status_code == status_code
    assert response.json()["status"] == "error"
    assert response.json()["error"]["code"] == code
