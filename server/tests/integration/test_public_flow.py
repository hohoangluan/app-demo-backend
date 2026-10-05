"""End-to-end request flow on real PostgreSQL, as an External API Client sees it.

phone registers -> glasses linked -> client POSTs a function -> delivery worker
pushes the command -> phone reports -> client reads the terminal status.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Any
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select, text, update

from app.application import create_app
from app.database import get_db_session
from app.models.enums import DeliveryState, RequestState
from app.models.operation import Operation
from app.workers.delivery import DeliveryWorker
from app.workers.timeout import TimeoutWorker
from tests.helpers import DEVICE_TOKEN, PUBLIC_TOKEN, make_settings

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

    from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

    from app.config import Settings
    from app.database import AsyncSessionFactory

USER_ID = "user-flow"
PHONE_ID = "phone-flow"
GLASSES_ID = "glasses-flow"
PUBLIC = {"Authorization": f"Bearer {PUBLIC_TOKEN}"}
DEVICE = {"Authorization": f"Bearer {DEVICE_TOKEN}"}


async def _truncate(engine: AsyncEngine) -> None:
    async with engine.begin() as connection:
        await connection.execute(text("TRUNCATE TABLE operations, devices, glasses_devices"))


@pytest.fixture
def flow_settings(test_database_url: str) -> Settings:
    """Return settings pointing at the integration-test database."""
    return make_settings(database_url=test_database_url)


@pytest.fixture
async def api(
    flow_settings: Settings,
    postgres_engine: AsyncEngine,
    postgres_session_factory: AsyncSessionFactory,
) -> AsyncIterator[AsyncClient]:
    """Client for an app wired to PostgreSQL, with one phone and one pair of glasses."""
    await _truncate(postgres_engine)
    app = create_app(flow_settings)

    async def session_override() -> AsyncIterator[AsyncSession]:
        async with postgres_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = session_override
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        registered = await client.post(
            "/api/v1/device/register",
            json={
                "user_id": USER_ID,
                "device_id": PHONE_ID,
                "platform": "android",
                "push_token": "push-token-flow",
            },
            headers=DEVICE,
        )
        assert registered.status_code == 200
        linked = await client.post(
            "/api/v1/device/glasses/link",
            json={"user_id": USER_ID, "device_id": GLASSES_ID},
            headers=DEVICE,
        )
        assert linked.status_code == 200
        yield client
    await _truncate(postgres_engine)


async def _status(api: AsyncClient, request_id: str) -> dict[str, Any]:
    response = await api.get(f"/api/v1/requests/{request_id}", headers=PUBLIC)
    assert response.status_code == 200
    data: dict[str, Any] = response.json()["data"]
    return data


def _report(request_id: str, **overrides: object) -> dict[str, Any]:
    report: dict[str, Any] = {
        "user_id": USER_ID,
        "device_id": PHONE_ID,
        "request_id": request_id,
        "action": "contact_call",
        "execution_state": "succeeded",
        "result": {"contact_found": True, "call_status": "calling"},
        "timestamp": datetime.now(UTC).isoformat(),
    }
    report.update(overrides)
    return report


async def _row(factory: AsyncSessionFactory, request_id: str) -> Operation:
    async with factory() as session:
        return (
            await session.execute(select(Operation).where(Operation.request_id == request_id))
        ).scalar_one()


async def test_request_runs_from_accept_to_succeeded(
    api: AsyncClient, flow_settings: Settings, postgres_session_factory: AsyncSessionFactory
) -> None:
    """Accept, deliver, report and read back a successful call."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "name": "Mẹ"}

    accepted = await api.post("/api/v1/service/contact/call", json=body, headers=PUBLIC)
    assert accepted.status_code == 202
    assert accepted.json()["data"]["operation"] == "contact_call"
    assert (await _status(api, request_id))["request_state"] == "processing"
    assert (await _row(postgres_session_factory, request_id)).params == {"name": "Mẹ"}

    assert await DeliveryWorker(postgres_session_factory, flow_settings).run_once() == 1
    delivered = await _row(postgres_session_factory, request_id)
    assert delivered.delivery_state is DeliveryState.SENT
    assert delivered.device_id == PHONE_ID

    reported = await api.post("/api/v1/device/report", json=_report(request_id), headers=DEVICE)
    assert reported.status_code == 200
    assert reported.json()["data"] == {"request_id": request_id, "report_received": True}

    final = await _status(api, request_id)
    assert final["request_state"] == "succeeded"
    assert final["result"] == {"contact_found": True, "call_status": "calling"}
    assert final["error"] is None


async def test_failed_report_is_published_as_public_error(
    api: AsyncClient, flow_settings: Settings, postgres_session_factory: AsyncSessionFactory
) -> None:
    """A device failure becomes the request's Public error."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "name": "Không có"}
    assert (await api.post("/api/v1/service/contact/call", json=body, headers=PUBLIC)).is_success
    await DeliveryWorker(postgres_session_factory, flow_settings).run_once()

    error = {"code": "CONTACT_NOT_FOUND", "message": "No contact", "details": {"name": "Không có"}}
    failed = _report(request_id, execution_state="failed", result=None, error=error)
    assert (await api.post("/api/v1/device/report", json=failed, headers=DEVICE)).is_success

    final = await _status(api, request_id)
    assert final["request_state"] == "failed"
    assert final["result"] is None
    assert final["error"] == error


async def test_duplicate_request_reuses_operation_and_conflict_returns_409(
    api: AsyncClient, flow_settings: Settings, postgres_session_factory: AsyncSessionFactory
) -> None:
    """A replay reuses the operation; a different payload with the same id is a 409."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "level": 40}

    first = await api.post("/api/v1/service/music/volume", json=body, headers=PUBLIC)
    replay = await api.post("/api/v1/service/music/volume", json=body, headers=PUBLIC)
    conflict = await api.post(
        "/api/v1/service/music/volume", json={**body, "level": 41}, headers=PUBLIC
    )

    assert first.status_code == replay.status_code == 202
    assert replay.json()["data"]["accepted_at"] == first.json()["data"]["accepted_at"]
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "REQUEST_ID_CONFLICT"
    assert await DeliveryWorker(postgres_session_factory, flow_settings).run_once() == 1
    assert await DeliveryWorker(postgres_session_factory, flow_settings).run_once() == 0


async def test_music_play_sends_spotify_uri_and_replay_is_not_a_conflict(
    api: AsyncClient, postgres_session_factory: AsyncSessionFactory
) -> None:
    """The server adds a Spotify URI to the command without breaking idempotency."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "song": "Lạc trôi"}

    first = await api.post("/api/v1/service/music/play", json=body, headers=PUBLIC)
    replay = await api.post("/api/v1/service/music/play", json=body, headers=PUBLIC)

    assert first.status_code == replay.status_code == 202
    params = (await _row(postgres_session_factory, request_id)).params
    assert params == {"song": "Lạc trôi", "spotify_uri": "spotify:search:L%E1%BA%A1c%20tr%C3%B4i"}


async def test_unlinked_glasses_are_rejected_before_any_operation_exists(
    api: AsyncClient, postgres_session_factory: AsyncSessionFactory
) -> None:
    """Unknown glasses get 404 and leave no operation behind."""
    request_id = str(uuid4())
    body = {"device_id": "glasses-unknown", "request_id": request_id}

    response = await api.post("/api/v1/service/location/get", json=body, headers=PUBLIC)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "GLASSES_DEVICE_NOT_LINKED"
    async with postgres_session_factory() as session:
        count = (await session.execute(text("SELECT count(*) FROM operations"))).scalar()
    assert count == 0


@pytest.mark.parametrize(
    "overrides",
    [
        {"device_id": "phone-other"},
        {"user_id": "user-other"},
        {"action": "music_stop"},
    ],
    ids=["wrong-device", "wrong-user", "wrong-action"],
)
async def test_report_from_wrong_identity_is_rejected_and_changes_nothing(
    api: AsyncClient,
    flow_settings: Settings,
    postgres_session_factory: AsyncSessionFactory,
    overrides: dict[str, str],
) -> None:
    """Reports from another device, user or action are refused."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "name": "Mẹ"}
    assert (await api.post("/api/v1/service/contact/call", json=body, headers=PUBLIC)).is_success
    await DeliveryWorker(postgres_session_factory, flow_settings).run_once()

    response = await api.post(
        "/api/v1/device/report", json=_report(request_id, **overrides), headers=DEVICE
    )

    assert response.status_code == 404
    assert (await _status(api, request_id))["request_state"] == "processing"


async def test_duplicate_report_is_accepted_but_a_different_one_conflicts(
    api: AsyncClient, flow_settings: Settings, postgres_session_factory: AsyncSessionFactory
) -> None:
    """An exact report replay is fine; a different outcome is a 409."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "name": "Mẹ"}
    assert (await api.post("/api/v1/service/contact/call", json=body, headers=PUBLIC)).is_success
    await DeliveryWorker(postgres_session_factory, flow_settings).run_once()

    report = _report(request_id)
    first = await api.post("/api/v1/device/report", json=report, headers=DEVICE)
    replay = await api.post("/api/v1/device/report", json=report, headers=DEVICE)
    other = await api.post(
        "/api/v1/device/report",
        json=_report(request_id, result={"contact_found": False}),
        headers=DEVICE,
    )

    assert first.status_code == replay.status_code == 200
    assert other.status_code == 409
    final = await _status(api, request_id)
    assert final["result"] == {"contact_found": True, "call_status": "calling"}


async def test_request_without_report_times_out_and_late_report_is_refused(
    api: AsyncClient, flow_settings: Settings, postgres_session_factory: AsyncSessionFactory
) -> None:
    """Silence past the deadline becomes timed_out, and a late report cannot change it."""
    request_id = str(uuid4())
    body = {"device_id": GLASSES_ID, "request_id": request_id, "name": "Mẹ"}
    assert (await api.post("/api/v1/service/contact/call", json=body, headers=PUBLIC)).is_success
    await DeliveryWorker(postgres_session_factory, flow_settings).run_once()
    async with postgres_session_factory() as session:
        await session.execute(
            update(Operation)
            .where(Operation.request_id == request_id)
            .values(expires_at=datetime.now(UTC) - timedelta(seconds=1))
        )
        await session.commit()

    assert await TimeoutWorker(postgres_session_factory, flow_settings).run_once() == 1
    late = await api.post("/api/v1/device/report", json=_report(request_id), headers=DEVICE)

    final = await _status(api, request_id)
    assert final["request_state"] == RequestState.TIMED_OUT.value
    assert final["error"]["code"] == "REPORT_TIMEOUT"
    assert late.status_code == 409
