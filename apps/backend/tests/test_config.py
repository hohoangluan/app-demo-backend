"""Configuration validation tests."""

import pytest
from pydantic import ValidationError

from app.config import AppEnvironment, DeliveryTransport, Settings


def valid_settings_data() -> dict[str, object]:
    """Build complete settings input without using environment state."""
    return {
        "app_env": AppEnvironment.TEST,
        "http_port": 8000,
        "database_url": "postgresql+asyncpg://test:test@localhost:5432/app_test",
        "public_api_token_hash": "test-only-public-hash",
        "device_api_token_hash": "test-only-device-hash",
        "field_encryption_key": "test-only-field-key",
        "delivery_transport": DeliveryTransport.FAKE,
    }


def test_settings_accept_complete_fake_transport_configuration() -> None:
    """Accept complete settings for the deterministic fake transport."""
    settings = Settings.model_validate(valid_settings_data())

    assert settings.app_env is AppEnvironment.TEST
    assert settings.delivery_transport is DeliveryTransport.FAKE
    assert settings.worker_batch_size == 20


def test_settings_reject_non_async_database_url() -> None:
    """Reject a PostgreSQL URL that cannot create an async engine."""
    values = valid_settings_data()
    values["database_url"] = "postgresql://test:test@localhost:5432/app_test"

    with pytest.raises(ValidationError, match="postgresql\\+asyncpg"):
        Settings.model_validate(values)


def test_settings_require_fcm_project_for_fcm_transport() -> None:
    """Reject incomplete FCM delivery configuration."""
    values = valid_settings_data()
    values["delivery_transport"] = DeliveryTransport.FCM

    with pytest.raises(ValidationError, match="FCM_PROJECT_ID"):
        Settings.model_validate(values)


def test_settings_require_complete_callback_configuration() -> None:
    """Reject partially configured callback delivery."""
    values = valid_settings_data()
    values["callback_url"] = "https://callback.example.test/result"

    with pytest.raises(ValidationError, match="must be configured together"):
        Settings.model_validate(values)


def test_settings_ignore_empty_optional_environment_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Treat optional variables rendered as empty strings as unset."""
    environment = {
        "APP_ENV": "test",
        "HTTP_PORT": "8000",
        "DATABASE_URL": "postgresql+asyncpg://test:test@localhost:5432/app_test",
        "PUBLIC_API_TOKEN_HASH": "test-only-public-hash",
        "DEVICE_API_TOKEN_HASH": "test-only-device-hash",
        "FIELD_ENCRYPTION_KEY": "test-only-field-key",
        "DELIVERY_TRANSPORT": "fake",
        "CALLBACK_URL": "",
        "CALLBACK_TOKEN": "",
        "CALLBACK_ALLOWED_HOSTS": "",
        "FCM_PROJECT_ID": "",
    }
    for name, value in environment.items():
        monkeypatch.setenv(name, value)

    settings = Settings()

    assert settings.callback_url is None
    assert settings.callback_token is None
    assert settings.callback_allowed_hosts is None
    assert settings.fcm_project_id is None
