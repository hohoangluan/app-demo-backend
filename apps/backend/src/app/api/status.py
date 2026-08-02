"""Public Status API router for checking request lifecycle state."""

from __future__ import annotations

from typing import Annotated
from uuid import UUID  # noqa: TC003

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.auth import ClientPrincipal, require_public_scope
from app.config import PublicApiScope, Settings, get_app_settings
from app.database import get_db_session
from app.errors import RequestNotFoundError
from app.schemas.common import (
    OkResponse,
    PublicError,
    RequestState,
    RequestStatusData,
)
from app.services.operation import OperationService

router = APIRouter(prefix="/api/v1/requests", tags=["status"])

_REQUEST_NOT_FOUND_MSG = "Request not found"


@router.get("/{request_id}", response_model=OkResponse[RequestStatusData[dict[str, object]]])
async def get_request_status(
    request_id: UUID,
    principal: Annotated[
        ClientPrincipal, Depends(require_public_scope(PublicApiScope.REQUESTS_READ))
    ],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_app_settings)],
) -> OkResponse[RequestStatusData[dict[str, object]]]:
    """Retrieve the current lifecycle status of an accepted request owned by the caller.

    Per ``CONTRACT_DECISIONS.md`` ``D-06``, returns ``404 REQUEST_NOT_FOUND``
    if the request does not exist OR belongs to another client.
    """
    service = OperationService(session, settings)
    operation = await service.get_status(client_id=principal.client_id, request_id=request_id)
    if operation is None:
        raise RequestNotFoundError(_REQUEST_NOT_FOUND_MSG)

    public_error: PublicError | None = None
    if operation.error is not None:
        raw_details = operation.error.get("details")
        details_dict = raw_details if isinstance(raw_details, dict) else {}
        public_error = PublicError(
            code=str(operation.error.get("code", "UNKNOWN_ERROR")),
            message=str(operation.error.get("message", "An error occurred")),
            details=details_dict,
        )

    data: RequestStatusData[dict[str, object]] = RequestStatusData(
        request_id=operation.request_id,
        operation=operation.operation,
        request_state=RequestState(operation.request_state.value),
        result=operation.result,
        error=public_error,
        created_at=operation.created_at,
        updated_at=operation.updated_at,
    )
    return OkResponse(data=data)
