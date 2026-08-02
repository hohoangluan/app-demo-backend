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
        "public_api_token_hash": "1" * 64,
        "public_api_client_id": "test-only-public-client",
        "public_api_scopes": {"service:execute", "requests:read"},
        "device_api_token_hash": "2" * 64,
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


def test_settings_require_fcm_project_for_fcm_transport(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reject incomplete FCM delivery configuration."""
    monkeypatch.delenv("FCM_PROJECT_ID", raising=False)
    values = valid_settings_data()
    values["delivery_transport"] = DeliveryTransport.FCM
    values["fcm_project_id"] = None

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
    monkeypatch.delenv("GOOGLE_APPLICATION_CREDENTIALS", raising=False)
    environment = {
        "APP_ENV": "test",
        "HTTP_PORT": "8000",
        "DATABASE_URL": "postgresql+asyncpg://test:test@localhost:5432/app_test",
        "PUBLIC_API_TOKEN_HASH": "1" * 64,
        "PUBLIC_API_CLIENT_ID": "test-only-public-client",
        "PUBLIC_API_SCOPES": "service:execute,requests:read",
        "DEVICE_API_TOKEN_HASH": "2" * 64,
        "FIELD_ENCRYPTION_KEY": "test-only-field-key",
        "DELIVERY_TRANSPORT": "fake",
        "CALLBACK_URL": "",
        "CALLBACK_TOKEN": "",
        "CALLBACK_ALLOWED_HOSTS": "",
        "FCM_PROJECT_ID": "",
    }
    for name, value in environment.items():
        monkeypatch.setenv(name, value)

    settings = Settings(_env_file=None)

    assert settings.callback_url is None
    assert settings.callback_token is None
    assert settings.callback_allowed_hosts is None
    assert settings.fcm_project_id is None



def test_settings_parse_comma_separated_public_api_scopes_from_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Parse a comma-separated PUBLIC_API_SCOPES environment value into a scope set."""
    environment = {
        "APP_ENV": "test",
        "HTTP_PORT": "8000",
        "DATABASE_URL": "postgresql+asyncpg://test:test@localhost:5432/app_test",
        "PUBLIC_API_TOKEN_HASH": "1" * 64,
        "PUBLIC_API_CLIENT_ID": "test-only-public-client",
        "PUBLIC_API_SCOPES": "service:execute,requests:read",
        "DEVICE_API_TOKEN_HASH": "2" * 64,
        "FIELD_ENCRYPTION_KEY": "test-only-field-key",
        "DELIVERY_TRANSPORT": "fake",
    }
    for name, value in environment.items():
        monkeypatch.setenv(name, value)

    settings = Settings()

    assert settings.public_api_client_id == "test-only-public-client"
    assert settings.public_api_scopes == frozenset({"service:execute", "requests:read"})


def test_settings_reject_empty_public_api_scopes() -> None:
    """Reject an empty PUBLIC_API_SCOPES set at startup."""
    values = valid_settings_data()
    values["public_api_scopes"] = set()

    with pytest.raises(ValidationError, match="must not be empty"):
        Settings.model_validate(values)


def test_settings_reject_unknown_public_api_scope() -> None:
    """Reject a PUBLIC_API_SCOPES entry outside the fixed P1 scope set."""
    values = valid_settings_data()
    values["public_api_scopes"] = {"service:execute", "admin:everything"}

    with pytest.raises(ValidationError, match="unknown scopes"):
        Settings.model_validate(values)


def test_settings_reject_malformed_public_api_token_hash() -> None:
    """Reject a PUBLIC_API_TOKEN_HASH that is not 64 lowercase hex characters."""
    values = valid_settings_data()
    values["public_api_token_hash"] = "not-a-valid-hash"

    with pytest.raises(ValidationError, match="64 lowercase hexadecimal"):
        Settings.model_validate(values)


def test_settings_reject_empty_public_api_client_id() -> None:
    """Reject a whitespace-only PUBLIC_API_CLIENT_ID."""
    values = valid_settings_data()
    values["public_api_client_id"] = "   "

    with pytest.raises(ValidationError, match="non-empty identifier"):
        Settings.model_validate(values)
