"""Operation acceptance service: fingerprint, idempotency, and initial state.

Scope: implements ``docs/p1-api-plan.md`` "Fingerprint canonicalization" and
"Idempotency behavior" for accepting one validated Public function request.
It computes the canonical request fingerprint, picks ``expires_at`` and
``callback_state``, and calls ``OperationRepository.insert_or_get`` --
exactly the pieces ``app/repositories/operation.py``'s module docstring
says the repository itself does *not* do. This service does not build an
HTTP response, does not decide a Public error code, does not call a router,
and does not commit the session; that remains the caller's (a future
router's, or a test's) responsibility, matching the repository layer's own
"caller still owns commit()" contract.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import TYPE_CHECKING

from app.models.enums import CallbackState
from app.repositories.operation import NewOperation, OperationInsertStatus, OperationRepository

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.actions import ActionRoute
    from app.config import Settings
    from app.models.operation import JsonObject, Operation
    from app.schemas.service_requests import ServiceRequest


def compute_request_fingerprint(
    *, client_id: str, route: ActionRoute, request: ServiceRequest
) -> str:
    """Compute the canonical idempotency fingerprint per ``docs/p1-api-plan.md``.

    Implements "Fingerprint canonicalization" steps 1-6 exactly:

    1. ``method`` is upper-cased (``route.method`` is already the fixed
       ``"POST"``; this just makes the rule explicit/defensive rather than
       trusting the enum's casing forever).
    2. ``path`` is the fixed route template (``route.path``), never a raw
       request path or alias.
    3. ``body_object = request.model_dump(mode="json", exclude_none=True)``
       -- Pydantic's own canonical JSON-mode serialization, taken *after*
       validation, so coerced types (e.g. ``UUID`` -> string) are what get
       hashed, not the raw client input.
    4. No additional trim/case-fold/Unicode-normalize step is applied here;
       that behavior belongs to the schema layer and is blocked on ``D-07``.
    5. The identity object ``{"body", "client_id", "method", "path"}`` is
       serialized with ``json.dumps(sort_keys=True,
       separators=(",", ":"), ensure_ascii=False, allow_nan=False)`` so key
       order, whitespace, and client-provided key order can never change
       the digest.
    6. SHA-256 over the UTF-8-encoded canonical bytes, returned as
       lowercase 64-character hex -- matching
       ``ck_operations_request_fingerprint``'s
       ``length(request_fingerprint) = 64`` constraint.

    ``request_id`` and ``user_id`` live inside ``body_object`` (they are
    ``ServiceRequest`` fields) and are therefore part of the fingerprint,
    exactly as the doc requires.
    """
    body_object = request.model_dump(mode="json", exclude_none=True)
    identity: dict[str, object] = {
        "body": body_object,
        "client_id": client_id,
        "method": route.method.upper(),
        "path": route.path,
    }
    canonical_bytes = json.dumps(
        identity,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical_bytes).hexdigest()


def _extract_business_params(request: ServiceRequest) -> JsonObject:
    """Return the persisted ``params``: validated body minus common fields.

    Per ``docs/p1-api-plan.md``'s ``music_volume`` slice: "Persist params
    chỉ gồm business params ... không lặp user_id, request_id, token hoặc
    client ID trong command params." Applied generically here for every
    ``ServiceRequest`` subclass, since each one only adds its
    operation-specific fields on top of the two common ``ServiceRequest``
    fields removed below.
    """
    body_object = request.model_dump(mode="json", exclude_none=True)
    body_object.pop("user_id", None)
    body_object.pop("request_id", None)
    return body_object


class AcceptOperationStatus(StrEnum):
    """Outcome of :meth:`OperationService.accept`.

    Mirrors :class:`app.repositories.operation.OperationInsertStatus`,
    collapsed to the two outcomes a Public router needs to distinguish:

    ``ACCEPTED``: covers both ``OperationInsertStatus.INSERTED`` and
    ``OperationInsertStatus.IDEMPOTENT_MATCH``. Per
    ``CONTRACT_DECISIONS.md`` ``D-10``, both render as ``HTTP 202`` using
    ``operation.created_at`` as ``accepted_at`` -- already the *original*
    acceptance time in both cases, because the idempotent-match branch
    inside :meth:`OperationRepository.insert_or_get` never touches the
    existing row. ``AcceptOperationResult.inserted`` distinguishes which
    branch fired, purely for caller/test observability; it never changes
    the HTTP response.

    ``CONFLICT``: covers ``OperationInsertStatus.FINGERPRINT_CONFLICT`` --
    the same ``request_id`` already exists with a different ``client_id``
    and/or ``request_fingerprint``. This service does not pick an HTTP
    status or Public error code for that case; it only exposes a distinct
    outcome plus the conflicting existing row so a future router can map it
    to ``409 REQUEST_ID_CONFLICT``.
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
    """Accept-or-reuse a validated Public function request as one operation.

    Sits directly on top of :class:`OperationRepository` (dependency
    direction: api -> service -> repository -> PostgreSQL). Never imports a
    router, never builds an HTTP response, and never commits the session --
    the caller (a future router, or a test) owns the transaction, exactly
    like the repository it wraps.
    """

    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        """Bind the service to a caller-owned session/transaction and validated settings.

        ``settings`` is only consulted for ``callback_url`` (to pick the
        initial ``callback_state``, see :meth:`_initial_callback_state`);
        the service does not otherwise depend on application configuration.
        """
        self._repository = OperationRepository(session)
        self._settings = settings

    async def accept(
        self, *, client_id: str, route: ActionRoute, request: ServiceRequest
    ) -> AcceptOperationResult:
        """Accept-or-reuse ``request`` as one operation, per ``docs/p1-api-plan.md``.

        Builds the ``NewOperation`` candidate (fingerprint, fixed
        mapping/timeout from ``route``, initial ``callback_state``) and
        delegates to :meth:`OperationRepository.insert_or_get` inside the
        caller's transaction. Does not commit.
        """
        fingerprint = compute_request_fingerprint(client_id=client_id, route=route, request=request)
        expires_at = datetime.now(UTC) + timedelta(seconds=route.timeout_seconds)
        candidate = NewOperation(
            request_id=request.request_id,
            client_id=client_id,
            user_id=request.user_id,
            operation=route.operation,
            action=route.action,
            params=_extract_business_params(request),
            request_fingerprint=fingerprint,
            callback_state=self._initial_callback_state(),
            expires_at=expires_at,
        )
        outcome = await self._repository.insert_or_get(candidate)

        if outcome.status is OperationInsertStatus.FINGERPRINT_CONFLICT:
            return AcceptOperationResult(
                status=AcceptOperationStatus.CONFLICT,
                operation=outcome.operation,
                inserted=False,
            )
        return AcceptOperationResult(
            status=AcceptOperationStatus.ACCEPTED,
            operation=outcome.operation,
            inserted=outcome.status is OperationInsertStatus.INSERTED,
        )

    def _initial_callback_state(self) -> CallbackState:
        """Pick the initial ``callback_state`` for a newly-accepted operation.

        Judgment call: ``docs/p1-api-plan.md``'s interfaces section mentions
        a ``callback_required: bool`` concept but never defines how P1
        derives it, and the callback worker itself is out of scope for this
        prototype phase (it does not exist yet). ``Settings.callback_url``
        being ``None`` is this codebase's existing signal for "callback
        delivery is unconfigured" (see
        ``Settings.validate_conditional_configuration``, which requires
        ``callback_url``/``callback_token``/``callback_allowed_hosts`` to be
        configured together or not at all), so it is reused here: no
        configured callback URL means every new operation starts
        ``CallbackState.NOT_REQUIRED`` (matching the existing PostgreSQL
        integration test fixtures' default in
        ``tests/integration/test_operation_repository.py``); a configured
        callback URL means ``CallbackState.PENDING`` so the future callback
        worker's claim query can find it once the operation goes terminal.
        """
        if self._settings.callback_url is None:
            return CallbackState.NOT_REQUIRED
        return CallbackState.PENDING
