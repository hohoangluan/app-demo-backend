"""Process liveness and dependency readiness probes."""

from enum import StrEnum

from fastapi import APIRouter, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.responses import Response

router = APIRouter(prefix="/health", tags=["health"])


class HealthStatus(StrEnum):
    """Observable health states."""

    OK = "ok"
    NOT_READY = "not_ready"


class HealthResponse(BaseModel):
    """Response returned by health probes."""

    status: HealthStatus


@router.get("/live")
async def liveness() -> HealthResponse:
    """Report whether the ASGI process can serve requests."""
    return HealthResponse(status=HealthStatus.OK)


@router.get(
    "/ready",
    response_model=HealthResponse,
    responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": HealthResponse}},
)
async def readiness(request: Request) -> Response:
    """Report whether application dependencies finished bootstrapping."""
    is_ready = bool(getattr(request.app.state, "ready", False))
    health_status = HealthStatus.OK if is_ready else HealthStatus.NOT_READY
    status_code = status.HTTP_200_OK if is_ready else status.HTTP_503_SERVICE_UNAVAILABLE
    payload = HealthResponse(status=health_status)
    return JSONResponse(status_code=status_code, content=payload.model_dump(mode="json"))
