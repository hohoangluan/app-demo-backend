"""Separated code-driven OpenAPI contract tests."""

from pathlib import Path
from typing import Any

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from scripts.export_openapi import find_stale_contracts

from app.contract_api import create_device_contract_app, create_public_contract_app

PUBLIC_PATHS = {
    "/api/v1/service/ride/quote",
    "/api/v1/service/ride/confirm",
    "/api/v1/service/music/play",
    "/api/v1/service/music/stop",
    "/api/v1/service/music/volume",
    "/api/v1/service/navigation/start",
    "/api/v1/service/navigation/stop",
    "/api/v1/service/emergency/call",
    "/api/v1/service/contact/call",
    "/api/v1/requests/{request_id}",
}
DEVICE_PATHS = {"/api/v1/device/register", "/api/v1/device/report"}


def operations(schema: dict[str, Any]) -> list[dict[str, Any]]:
    """Return every HTTP operation in an OpenAPI schema."""
    return [
        operation
        for path_item in schema["paths"].values()
        for method, operation in path_item.items()
        if method in {"get", "post"}
    ]


def test_public_and_device_contract_paths_are_separated(test_app: FastAPI) -> None:
    """Keep external, Android-only, and runtime routes separated."""
    public_schema = create_public_contract_app().openapi()
    device_schema = create_device_contract_app().openapi()

    assert set(public_schema["paths"]) == PUBLIC_PATHS
    assert set(device_schema["paths"]) == DEVICE_PATHS
    assert set(public_schema["paths"]).isdisjoint(device_schema["paths"])
    assert set(test_app.openapi()["paths"]) == {
        "/health/live",
        "/health/ready",
        "/api/v1/requests/{request_id}",
        "/api/v1/service/music/volume",
    }


def test_contracts_use_separate_http_bearer_security_schemes() -> None:
    """Require the correct bearer scheme on every operation in each contract."""
    public_schema = create_public_contract_app().openapi()
    device_schema = create_device_contract_app().openapi()

    assert public_schema["components"]["securitySchemes"] == {
        "PublicBearerAuth": {"type": "http", "scheme": "bearer"}
    }
    assert device_schema["components"]["securitySchemes"] == {
        "DeviceBearerAuth": {"type": "http", "scheme": "bearer"}
    }
    assert all(
        operation["security"] == [{"PublicBearerAuth": []}]
        for operation in operations(public_schema)
    )
    assert all(
        operation["security"] == [{"DeviceBearerAuth": []}]
        for operation in operations(device_schema)
    )


def test_public_validation_uses_documented_400_and_never_exposes_422() -> None:
    """Document INVALID_REQUEST at HTTP 400 instead of FastAPI's default 422."""
    schema = create_public_contract_app().openapi()

    for path, path_item in schema["paths"].items():
        operation = (
            path_item["get"] if path == "/api/v1/requests/{request_id}" else path_item["post"]
        )
        assert "422" not in operation["responses"]
        if "post" in path_item:
            assert set(operation["responses"]) == {"202", "400"}
            example = operation["responses"]["400"]["content"]["application/json"]["example"]
            assert example["error"]["code"] == "INVALID_REQUEST"

    assert "HTTPValidationError" not in schema["components"]["schemas"]
    assert "ValidationError" not in schema["components"]["schemas"]


def test_device_contract_does_not_publish_undocumented_422() -> None:
    """Keep implicit FastAPI validation responses out of the Device contract."""
    schema = create_device_contract_app().openapi()

    assert all("422" not in operation["responses"] for operation in operations(schema))
    assert "HTTPValidationError" not in schema["components"]["schemas"]
    assert "ValidationError" not in schema["components"]["schemas"]


async def test_public_contract_app_returns_400_for_invalid_request() -> None:
    """Match the documented validation status in contract-app behavior."""
    application = create_public_contract_app()
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/v1/service/music/volume",
            headers={"Authorization": "Bearer test-token"},
            json={"user_id": "user-123", "request_id": "not-a-uuid", "level": 101},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_committed_openapi_artifacts_are_up_to_date() -> None:
    """Fail when generated Public or Device YAML differs from the repository."""
    contracts_directory = Path(__file__).resolve().parents[3] / "contracts"

    assert find_stale_contracts(contracts_directory) == ()
