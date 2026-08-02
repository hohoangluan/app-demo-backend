"""Validated application configuration loaded from environment variables."""

from enum import StrEnum
from functools import lru_cache
from pathlib import Path
from typing import Self

from pydantic import Field, HttpUrl, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


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
