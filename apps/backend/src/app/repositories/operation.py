"""Operation repository: atomic insert-or-read and ownership-filtered lookup.

Scope: this repository only implements the primitives needed by
``docs/p1-database-plan.md`` transaction design section "A. Accept request và
idempotency" (task ``P1-DB-07``). It does not compute the idempotency
fingerprint, does not pick a timeout, does not decide HTTP status codes or
Public error codes, and does not commit the session — all of that is the
caller's (service layer's) responsibility. Delivery-claim, callback-claim,
and report/timeout transitions are out of scope (future tasks
``P1-DB-08``/``09``/``10``).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.models.enums import CallbackState, DeliveryState, RequestState
from app.models.operation import JsonObject, Operation

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.actions import Action
    from app.actions import Operation as OperationName


class OperationInsertStatus(StrEnum):
    """Outcome of :meth:`OperationRepository.insert_or_get`.

    ``INSERTED``: no prior row existed for ``request_id``; the candidate row
    is now persisted (the caller must still ``commit()``).

    ``IDEMPOTENT_MATCH``: a row already existed with the same ``request_id``,
    the same ``client_id``, and the same ``request_fingerprint`` as the
    candidate. The existing row is returned unchanged (``created_at`` and
    every other field are untouched).

    ``FINGERPRINT_CONFLICT``: a row already existed with the same
    ``request_id`` but a different ``client_id`` and/or
    ``request_fingerprint``. The existing row is returned so the caller has
    it available, but no HTTP status or Public error code is decided here.
    """

    INSERTED = "inserted"
    IDEMPOTENT_MATCH = "idempotent_match"
    FINGERPRINT_CONFLICT = "fingerprint_conflict"


@dataclass(frozen=True, slots=True)
class OperationInsertOutcome:
    """Typed result of an insert-or-read attempt: status plus the row."""

    status: OperationInsertStatus
    operation: Operation


@dataclass(frozen=True, slots=True)
class NewOperation:
    """Fields the caller must supply to attempt inserting an operation row.

    The repository never computes any of these: the fingerprint, timeout
    (``expires_at``), and ``callback_state`` policy are all decided by the
    service layer before calling :meth:`OperationRepository.insert_or_get`.
    """

    request_id: UUID
    client_id: str
    user_id: str
    operation: OperationName
    action: Action
    params: JsonObject
    request_fingerprint: str
    callback_state: CallbackState
    expires_at: datetime


class OperationRepository:
    """Query/locking/persistence primitives for the ``operations`` table."""

    def __init__(self, session: AsyncSession) -> None:
        """Bind the repository to the caller-owned session/transaction."""
        self._session = session

    async def insert_or_get(self, candidate: NewOperation) -> OperationInsertOutcome:
        """Attempt ``INSERT ... ON CONFLICT (request_id) DO NOTHING RETURNING``.

        On success, the candidate row is persisted with
        ``request_state=processing``, ``delivery_state=received``, and
        ``next_delivery_at`` set equal to ``created_at`` (both bound to the
        same ``now()`` expression evaluated once for this statement), per
        ``docs/p1-database-plan.md`` transaction design "A". On conflict, the
        existing row is read back (in the same transaction) and classified
        as an idempotent match or a fingerprint conflict; neither branch
        mutates the existing row. The caller still owns ``commit()``.
        """
        now_expr = func.now()
        insert_statement = (
            pg_insert(Operation)
            .values(
                request_id=candidate.request_id,
                client_id=candidate.client_id,
                user_id=candidate.user_id,
                operation=candidate.operation,
                action=candidate.action,
                params=candidate.params,
                request_fingerprint=candidate.request_fingerprint,
                request_state=RequestState.PROCESSING,
                delivery_state=DeliveryState.RECEIVED,
                next_delivery_at=now_expr,
                callback_state=candidate.callback_state,
                expires_at=candidate.expires_at,
                created_at=now_expr,
                updated_at=now_expr,
            )
            .on_conflict_do_nothing(index_elements=[Operation.request_id])
            .returning(Operation)
        )
        inserted_result = await self._session.execute(insert_statement)
        inserted_row = inserted_result.scalars().first()
        if inserted_row is not None:
            return OperationInsertOutcome(OperationInsertStatus.INSERTED, inserted_row)

        existing_row = (
            await self._session.execute(
                select(Operation).where(Operation.request_id == candidate.request_id)
            )
        ).scalar_one()

        if (
            existing_row.client_id == candidate.client_id
            and existing_row.request_fingerprint == candidate.request_fingerprint
        ):
            return OperationInsertOutcome(OperationInsertStatus.IDEMPOTENT_MATCH, existing_row)
        return OperationInsertOutcome(OperationInsertStatus.FINGERPRINT_CONFLICT, existing_row)

    async def get_by_request_id(self, *, client_id: str, request_id: UUID) -> Operation | None:
        """Return the operation owned by ``client_id``, or ``None``.

        Ownership is filtered in the query's ``WHERE`` clause (not checked
        in Python after fetching), so a request ID that exists but belongs
        to a different client returns ``None`` rather than the row.
        """
        result = await self._session.execute(
            select(Operation).where(
                Operation.request_id == request_id,
                Operation.client_id == client_id,
            )
        )
        return result.scalar_one_or_none()
