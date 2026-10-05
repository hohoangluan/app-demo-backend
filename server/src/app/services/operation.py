"""Accept Public function requests as operations: identity, idempotency, initial state.

Flow of :meth:`OperationService.submit`::

    glasses device_id -> owning user_id
    request body      -> fingerprint + business params (+ resolved Spotify URI)
    insert-or-get     -> new row | idempotent replay | 409 conflict
    commit            -> delivery worker picks the row up
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import TYPE_CHECKING, Protocol

from app.actions import ACTION_ROUTES_BY_ACTION
from app.errors import RequestIdConflictError
from app.models.enums import CallbackState
from app.repositories.glasses_device import GlassesDeviceRepository
from app.repositories.operation import NewOperation, OperationInsertStatus, OperationRepository
from app.schemas.service_requests import MusicPlayRequest
from app.services.glasses import resolve_glasses_device_owner

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.actions import Action, ActionRoute
    from app.config import Settings
    from app.models.operation import JsonObject, Operation
    from app.schemas.service_requests import ServiceRequest


class MusicCatalog(Protocol):
    """Resolves a free-text song to a URI the phone's music app can open."""

    async def resolve_track_uri(self, song: str) -> str:
        """Return a ``spotify:`` URI for ``song``."""
        ...


def compute_request_fingerprint(
    *, client_id: str, route: ActionRoute, request: ServiceRequest
) -> str:
    """Return the SHA-256 idempotency fingerprint of one validated request.

    Hashes ``{body, client_id, method, path}`` as canonical JSON (sorted keys,
    compact separators), where ``body`` is the validated request including
    ``request_id`` and ``device_id``. Key order and whitespace in the client
    payload therefore never change the digest.
    """
    identity = {
        "body": request.model_dump(mode="json", exclude_none=True),
        "client_id": client_id,
        "method": route.method.upper(),
        "path": route.path,
    }
    canonical = json.dumps(
        identity, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def business_params(request: ServiceRequest) -> JsonObject:
    """Return the command params sent to the phone: the body minus routing fields."""
    params = request.model_dump(mode="json", exclude_none=True)
    params.pop("device_id", None)
    params.pop("request_id", None)
    return params


class AcceptOperationStatus(StrEnum):
    """Outcome of :meth:`OperationService.accept`.

    ``ACCEPTED`` covers both a new row and an idempotent replay (same client
    and fingerprint); ``CONFLICT`` means the ``request_id`` exists with a
    different client or payload.
    """

    ACCEPTED = "accepted"
    CONFLICT = "conflict"


@dataclass(frozen=True, slots=True)
class AcceptOperationResult:
    """Typed result of :meth:`OperationService.accept`."""

    status: AcceptOperationStatus
    operation: Operation
    inserted: bool


class OperationService:
    """Public-request use cases on top of :class:`OperationRepository`."""

    def __init__(
        self,
        session: AsyncSession,
        settings: Settings,
        music_catalog: MusicCatalog | None = None,
    ) -> None:
        """Bind the request's session, settings and optional music catalog."""
        self._session = session
        self._repository = OperationRepository(session)
        self._settings = settings
        self._music_catalog = music_catalog

    async def submit(self, *, client_id: str, action: Action, request: ServiceRequest) -> Operation:
        """Accept ``request`` for ``action`` and commit it.

        Raises ``GlassesDeviceNotLinkedError`` when ``device_id`` has no active
        pairing and ``RequestIdConflictError`` when the ``request_id`` was used
        for a different payload. A replay of the same request returns the
        original operation unchanged.
        """
        route = ACTION_ROUTES_BY_ACTION[action]
        user_id = await resolve_glasses_device_owner(
            GlassesDeviceRepository(self._session), device_id=request.device_id
        )
        params = business_params(request)
        if (
            isinstance(request, MusicPlayRequest)
            and not request.spotify_uri
            and self._music_catalog is not None
        ):
            params["spotify_uri"] = await self._music_catalog.resolve_track_uri(request.song)

        result = await self.accept(
            client_id=client_id, user_id=user_id, route=route, request=request, params=params
        )
        if result.status is AcceptOperationStatus.CONFLICT:
            message = "Existing request ID has different fingerprint or client"
            raise RequestIdConflictError(message)
        await self._session.commit()
        return result.operation

    async def accept(
        self,
        *,
        client_id: str,
        user_id: str,
        route: ActionRoute,
        request: ServiceRequest,
        params: JsonObject | None = None,
    ) -> AcceptOperationResult:
        """Insert-or-get one operation inside the caller's transaction (no commit).

        The fingerprint always covers the client's own body, so server-side
        param enrichment can never turn an honest retry into a conflict.
        """
        candidate = NewOperation(
            request_id=request.request_id,
            client_id=client_id,
            user_id=user_id,
            operation=route.operation,
            action=route.action,
            params=params if params is not None else business_params(request),
            request_fingerprint=compute_request_fingerprint(
                client_id=client_id, route=route, request=request
            ),
            callback_state=(
                CallbackState.NOT_REQUIRED
                if self._settings.callback_url is None
                else CallbackState.PENDING
            ),
            expires_at=datetime.now(UTC) + timedelta(seconds=route.timeout_seconds),
        )
        outcome = await self._repository.insert_or_get(candidate)
        if outcome.status is OperationInsertStatus.FINGERPRINT_CONFLICT:
            return AcceptOperationResult(
                status=AcceptOperationStatus.CONFLICT, operation=outcome.operation, inserted=False
            )
        return AcceptOperationResult(
            status=AcceptOperationStatus.ACCEPTED,
            operation=outcome.operation,
            inserted=outcome.status is OperationInsertStatus.INSERTED,
        )

    async def get_status(self, *, client_id: str, request_id: UUID) -> Operation | None:
        """Return the caller's operation; missing and foreign requests both yield ``None``."""
        return await self._repository.get_by_request_id(client_id=client_id, request_id=request_id)
