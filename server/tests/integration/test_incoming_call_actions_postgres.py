"""Persist call answer/reject controls through the real PostgreSQL operation lifecycle."""

from __future__ import annotations

from typing import TYPE_CHECKING
from uuid import uuid4

import pytest

from app.actions import ACTION_ROUTE_MAP, Action, HttpMethod, Operation
from app.schemas.service_requests import CallAnswerRequest, CallRejectRequest
from app.services.operation import AcceptOperationStatus, OperationService

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.config import Settings


@pytest.mark.parametrize(
    ("path", "request_type", "operation", "action"),
    [
        (
            "/api/v1/service/call/answer",
            CallAnswerRequest,
            Operation.CALL_ANSWER,
            Action.CALL_ANSWER,
        ),
        (
            "/api/v1/service/call/reject",
            CallRejectRequest,
            Operation.CALL_REJECT,
            Action.CALL_REJECT,
        ),
    ],
)
async def test_call_control_persists_as_deliverable_operation(  # noqa: PLR0913, PLR0917
    db_session: AsyncSession,
    settings: Settings,
    path: str,
    request_type: type[CallAnswerRequest | CallRejectRequest],
    operation: Operation,
    action: Action,
) -> None:
    """Prove both new CHECK-constraint values survive a real insert."""
    route = ACTION_ROUTE_MAP[(HttpMethod.POST, path)]
    request = request_type(device_id="glasses-1", request_id=uuid4())
    result = await OperationService(db_session, settings).accept(
        client_id="glasses-server",
        user_id="user-1",
        route=route,
        request=request,
    )
    await db_session.commit()

    assert result.status is AcceptOperationStatus.ACCEPTED
    assert result.operation.operation is operation
    assert result.operation.action is action
    assert result.operation.params == {}
