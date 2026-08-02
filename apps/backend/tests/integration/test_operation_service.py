"""PostgreSQL integration tests for ``OperationService`` (task in this session).

Exercises ``OperationService.accept`` -- fingerprint computation, idempotent
reuse (``CONTRACT_DECISIONS.md`` ``D-10``), and fingerprint-conflict
detection -- against a real ``OperationRepository``/``AsyncSession``, since
the underlying ``INSERT ... ON CONFLICT`` idempotency behavior this service
relies on is itself only proven correct against real PostgreSQL (see
``tests/integration/test_operation_repository.py``).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import uuid4

from app.actions import ACTION_ROUTES
from app.actions import Operation as OperationName
from app.models.enums import CallbackState
from app.schemas.service_requests import MusicVolumeRequest, VolumeDirection
from app.services.operation import AcceptOperationStatus, OperationService

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.config import Settings

MUSIC_VOLUME_ROUTE = next(
    route for route in ACTION_ROUTES if route.operation is OperationName.MUSIC_VOLUME
)


async def test_accept_new_request_inserts_and_computes_expiry(
    db_session: AsyncSession, settings: Settings
) -> None:
    """A brand-new request is accepted, persisted, and expires after the route's timeout."""
    service = OperationService(db_session, settings)
    request = MusicVolumeRequest(user_id="user-1", request_id=uuid4(), level=70)
    before = datetime.now(UTC)

    result = await service.accept(client_id="client-1", route=MUSIC_VOLUME_ROUTE, request=request)
    await db_session.commit()

    assert result.status is AcceptOperationStatus.ACCEPTED
    assert result.inserted is True
    assert result.operation.request_id == request.request_id
    assert result.operation.client_id == "client-1"
    assert result.operation.user_id == "user-1"
    assert result.operation.operation is OperationName.MUSIC_VOLUME
    assert result.operation.params == {"level": 70}
    assert len(result.operation.request_fingerprint) == 64
    assert result.operation.callback_state is CallbackState.NOT_REQUIRED
    expected_expiry = before + timedelta(seconds=MUSIC_VOLUME_ROUTE.timeout_seconds)
    assert abs((result.operation.expires_at - expected_expiry).total_seconds()) < 5


async def test_accept_duplicate_request_reuses_original_created_at(
    db_session: AsyncSession, settings: Settings
) -> None:
    """A byte-identical retry is idempotent and keeps the original acceptance time (D-10)."""
    service = OperationService(db_session, settings)
    request_id = uuid4()
    request = MusicVolumeRequest(
        user_id="user-1", request_id=request_id, direction=VolumeDirection.UP
    )

    first_result = await service.accept(
        client_id="client-1", route=MUSIC_VOLUME_ROUTE, request=request
    )
    await db_session.commit()
    original_created_at = first_result.operation.created_at
    assert first_result.inserted is True

    second_result = await service.accept(
        client_id="client-1", route=MUSIC_VOLUME_ROUTE, request=request
    )
    await db_session.commit()

    assert second_result.status is AcceptOperationStatus.ACCEPTED
    assert second_result.inserted is False
    assert second_result.operation.request_id == request_id
    assert second_result.operation.created_at == original_created_at


async def test_accept_conflicting_payload_returns_conflict(
    db_session: AsyncSession, settings: Settings
) -> None:
    """The same request ID with a different payload is a fingerprint conflict, not a reuse."""
    service = OperationService(db_session, settings)
    request_id = uuid4()
    first_request = MusicVolumeRequest(user_id="user-1", request_id=request_id, level=50)
    second_request = MusicVolumeRequest(user_id="user-1", request_id=request_id, level=90)

    first_result = await service.accept(
        client_id="client-1", route=MUSIC_VOLUME_ROUTE, request=first_request
    )
    await db_session.commit()
    assert first_result.status is AcceptOperationStatus.ACCEPTED

    second_result = await service.accept(
        client_id="client-1", route=MUSIC_VOLUME_ROUTE, request=second_request
    )
    await db_session.commit()

    assert second_result.status is AcceptOperationStatus.CONFLICT
    assert second_result.operation.request_id == request_id
    assert second_result.operation.params == {"level": 50}
