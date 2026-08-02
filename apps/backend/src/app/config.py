"""Validated application configuration loaded from environment variables."""

import re
from enum import StrEnum
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Self

from fastapi import Request
from pydantic import Field, HttpUrl, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class AppEnvironment(StrEnum):
    """Supported deployment environments."""

    LOCAL = "local"
    TEST = "test"
    DEMO = "demo"


class DeliveryTransport(StrEnum):
    """Supported command delivery transports."""

    FAKE = "fake"
    FCM = "fcm"


class LogLevel(StrEnum):
    """Supported application log levels."""

    DEBUG = "debug"
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class PublicApiScope(StrEnum):
    """Public API bearer scopes defined by ``docs/p1-api-plan.md``.

    ``SERVICE_EXECUTE`` gates every ``POST /api/v1/service/*`` function
    endpoint; ``REQUESTS_READ`` gates ``GET /api/v1/requests/{request_id}``.
    """

    SERVICE_EXECUTE = "service:execute"
    REQUESTS_READ = "requests:read"


# Exactly 64 lowercase hexadecimal characters: the hex encoding of a 32-byte
# HMAC-SHA256 digest, per docs/p1-api-plan.md "Bearer authentication" ->
# "Provisioning format" steps 1-3.
_TOKEN_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def _validate_token_hash_format(value: SecretStr, *, field_name: str) -> SecretStr:
    """Reject a token hash that is not exactly 64 lowercase hex characters.

    A value matching this pattern always decodes cleanly via
    ``bytes.fromhex`` into exactly 32 bytes, so no separate decode step can
    fail later. Applied identically to both ``PUBLIC_API_TOKEN_HASH`` (per
    the doc's explicit config-validation steps) and ``DEVICE_API_TOKEN_HASH``
    (a judgment call: the doc only spells out the format for the Public
    token, but both hashes are produced by the same domain-separated
    HMAC-SHA256 scheme described in "Provisioning format", so holding the
    Device hash to the same fail-fast format check is the conservative,
    consistent choice rather than leaving it unvalidated).
    """
    if not _TOKEN_HASH_PATTERN.fullmatch(value.get_secret_value()):
        message = f"{field_name} must be exactly 64 lowercase hexadecimal characters"
        raise ValueError(message)
    return value


def _parse_public_api_scopes(value: object) -> frozenset[str]:
    """Parse ``PUBLIC_API_SCOPES`` into an immutable, validated scope set.

    Accepts a comma-separated string (the environment-variable shape) or an
    existing iterable of strings (convenient for tests constructing
    ``Settings`` directly). Rejects an empty set, any empty/whitespace-only
    item, duplicate items, and any item outside :class:`PublicApiScope`, per
    docs/p1-api-plan.md config-validation step 5.
    """
    if isinstance(value, str):
        items = [item.strip() for item in value.split(",")]
    elif isinstance(value, list | tuple | set | frozenset):
        items = [str(item).strip() for item in value]
    else:
        message = "PUBLIC_API_SCOPES must be a comma-separated string or an iterable of strings"
        raise TypeError(message)

    if not items or any(not item for item in items):
        message = "PUBLIC_API_SCOPES must not be empty and must not contain empty items"
        raise ValueError(message)
    if len(items) != len(set(items)):
        message = "PUBLIC_API_SCOPES must not contain duplicate scopes"
        raise ValueError(message)
    known_scopes = {scope.value for scope in PublicApiScope}
    unknown_scopes = sorted(set(items) - known_scopes)
    if unknown_scopes:
        message = f"PUBLIC_API_SCOPES contains unknown scopes: {unknown_scopes}"
        raise ValueError(message)
    return frozenset(items)


class Settings(BaseSettings):
    """Runtime configuration for the backend process."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_ignore_empty=True,
        extra="ignore",
        case_sensitive=False,
    )

    app_env: AppEnvironment
    http_port: int = Field(ge=1, le=65_535)
    database_url: str
    public_api_token_hash: SecretStr
    public_api_client_id: str
    # NoDecode: pydantic-settings JSON-decodes complex-typed env values by
    # default (e.g. it would require PUBLIC_API_SCOPES to be a JSON array
    # string). NoDecode passes the raw comma-separated env string straight
    # to the "before" validator below instead.
    public_api_scopes: Annotated[frozenset[str], NoDecode]
    device_api_token_hash: SecretStr
    field_encryption_key: SecretStr
    delivery_transport: DeliveryTransport

    fcm_project_id: str | None = None
    google_application_credentials: Path | None = None

    worker_poll_seconds: float = Field(default=0.5, gt=0)
    worker_batch_size: int = Field(default=20, ge=1)
    delivery_lease_seconds: int = Field(default=30, ge=1)
    callback_lease_seconds: int = Field(default=30, ge=1)

    callback_url: HttpUrl | None = None
    callback_token: SecretStr | None = None
    callback_allowed_hosts: str | None = None

    demo_time_scale: float = Field(default=1.0, gt=0)
    log_level: LogLevel = LogLevel.INFO

    @field_validator("database_url")
    @classmethod
    def require_async_postgresql_url(cls, value: str) -> str:
        """Reject database URLs that cannot use SQLAlchemy's asyncpg driver."""
        if not value.startswith("postgresql+asyncpg://"):
            message = "DATABASE_URL must use the postgresql+asyncpg scheme"
            raise ValueError(message)
        return value

    @field_validator("public_api_token_hash")
    @classmethod
    def validate_public_api_token_hash_format(cls, value: SecretStr) -> SecretStr:
        """Enforce the 64-hex-character digest format at startup, before database/worker."""
        return _validate_token_hash_format(value, field_name="PUBLIC_API_TOKEN_HASH")

    @field_validator("device_api_token_hash")
    @classmethod
    def validate_device_api_token_hash_format(cls, value: SecretStr) -> SecretStr:
        """Enforce the 64-hex-character digest format at startup, before database/worker."""
        return _validate_token_hash_format(value, field_name="DEVICE_API_TOKEN_HASH")

    @field_validator("public_api_client_id")
    @classmethod
    def require_non_empty_public_api_client_id(cls, value: str) -> str:
        """Require ``PUBLIC_API_CLIENT_ID`` to be a non-empty configured identifier."""
        trimmed = value.strip()
        if not trimmed:
            message = "PUBLIC_API_CLIENT_ID must be a non-empty identifier"
            raise ValueError(message)
        return trimmed

    @field_validator("public_api_scopes", mode="before")
    @classmethod
    def parse_public_api_scopes(cls, value: object) -> frozenset[str]:
        """Parse and validate ``PUBLIC_API_SCOPES`` before pydantic's set coercion."""
        return _parse_public_api_scopes(value)

    @model_validator(mode="after")
    def validate_conditional_configuration(self) -> Self:
        """Require complete provider and callback settings when those modes are enabled."""
        if self.delivery_transport is DeliveryTransport.FCM and not self.fcm_project_id:
            message = "FCM_PROJECT_ID is required when DELIVERY_TRANSPORT=fcm"
            raise ValueError(message)

        callback_values = (
            self.callback_url,
            self.callback_token,
            self.callback_allowed_hosts,
        )
        if any(value is not None for value in callback_values) and not all(
            value is not None for value in callback_values
        ):
            message = (
                "CALLBACK_URL, CALLBACK_TOKEN, and CALLBACK_ALLOWED_HOSTS "
                "must be configured together"
            )
            raise ValueError(message)

        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Load and cache validated process configuration."""
    return Settings()


def get_app_settings(request: Request) -> Settings:
    """Return validated application settings attached to application state."""
    settings: Settings | None = getattr(request.app.state, "settings", None)
    if settings is None:
        return get_settings()
    return settings
