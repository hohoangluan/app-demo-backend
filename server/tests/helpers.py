"""Shared test credentials and settings."""

from __future__ import annotations

from app.auth import DEVICE_API_DOMAIN, PUBLIC_API_DOMAIN, token_digest
from app.config import AppEnvironment, DeliveryTransport, Settings

PUBLIC_TOKEN = "test-only-public-token"
DEVICE_TOKEN = "test-only-device-token"
PUBLIC_CLIENT_ID = "test-only-public-client"


def make_settings(**overrides: object) -> Settings:
    """Build test settings whose token hashes match ``PUBLIC_TOKEN``/``DEVICE_TOKEN``."""
    values: dict[str, object] = {
        "app_env": AppEnvironment.TEST,
        "http_port": 8000,
        "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
        "public_api_token_hash": token_digest(PUBLIC_TOKEN, PUBLIC_API_DOMAIN).hex(),
        "public_api_client_id": PUBLIC_CLIENT_ID,
        "public_api_scopes": {"service:execute", "requests:read"},
        "device_api_token_hash": token_digest(DEVICE_TOKEN, DEVICE_API_DOMAIN).hex(),
        "field_encryption_key": "test-only-field-key",
        "delivery_transport": DeliveryTransport.FAKE,
    }
    values.update(overrides)
    return Settings.model_validate(values)
