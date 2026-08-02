"""Health endpoint tests."""

from fastapi import FastAPI, status
from httpx import AsyncClient


async def test_liveness_reports_running_process(client: AsyncClient) -> None:
    """Return success while the ASGI process is serving requests."""
    response = await client.get("/health/live")

    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {"status": "ok"}


async def test_readiness_rejects_traffic_before_dependencies_bootstrap(
    client: AsyncClient,
) -> None:
    """Return unavailable until PostgreSQL and workers are ready."""
    response = await client.get("/health/ready")

    assert response.status_code == status.HTTP_503_SERVICE_UNAVAILABLE
    assert response.json() == {"status": "not_ready"}


async def test_readiness_accepts_traffic_after_dependencies_bootstrap(
    client: AsyncClient,
    test_app: FastAPI,
) -> None:
    """Return success after the future lifespan marks dependencies ready."""
    test_app.state.ready = True

    response = await client.get("/health/ready")

    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {"status": "ok"}
