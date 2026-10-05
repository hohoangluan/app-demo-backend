"""Bearer authentication dependencies for the Public, Device and user-session APIs.

Public and Device tokens are shared secrets configured as
``HMAC-SHA256(key=raw token, message=domain)`` hex digests. The two surfaces
use different domain strings, so reusing one raw token for both still yields
different digests. Every failure path returns the same ``401`` response.
"""

from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Annotated
from uuid import UUID  # noqa: TC003

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession  # noqa: TC002

from app.database import get_db_session
from app.repositories.session import SessionRepository
from app.security import hash_token

if TYPE_CHECKING:
    from collections.abc import Awaitable, Callable

    from app.config import Settings

PUBLIC_API_DOMAIN = b"app-demo-auth/v1/public-api"
DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"

_bearer_scheme = HTTPBearer(auto_error=False)
BearerCredentials = Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)]


def token_digest(raw_token: str, domain: bytes) -> bytes:
    """Return ``HMAC-SHA256(key=raw_token, message=domain)``; the token is never normalized."""
    return hmac.new(raw_token.encode("utf-8"), domain, hashlib.sha256).digest()


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="UNAUTHORIZED",
        headers={"WWW-Authenticate": "Bearer"},
    )


@dataclass(frozen=True, slots=True)
class ClientPrincipal:
    """Authenticated Public API client identity and granted scopes."""

    client_id: str
    scopes: frozenset[str]


@dataclass(frozen=True, slots=True)
class PublicApiAuthenticator:
    """Public API credential decoded once per application instance."""

    client_id: str
    scopes: frozenset[str]
    token_digest: bytes

    @classmethod
    def from_settings(cls, settings: Settings) -> PublicApiAuthenticator:
        """Decode the configured digest from validated settings."""
        return cls(
            client_id=settings.public_api_client_id,
            scopes=settings.public_api_scopes,
            token_digest=bytes.fromhex(settings.public_api_token_hash.get_secret_value()),
        )

    def authenticate(self, raw_token: str) -> ClientPrincipal | None:
        """Constant-time verify ``raw_token``; ``None`` means rejected."""
        if hmac.compare_digest(token_digest(raw_token, PUBLIC_API_DOMAIN), self.token_digest):
            return ClientPrincipal(client_id=self.client_id, scopes=self.scopes)
        return None


@dataclass(frozen=True, slots=True)
class DeviceApiAuthenticator:
    """Device API credential: one shared secret, no scopes."""

    token_digest: bytes

    @classmethod
    def from_settings(cls, settings: Settings) -> DeviceApiAuthenticator:
        """Decode the configured digest from validated settings."""
        return cls(token_digest=bytes.fromhex(settings.device_api_token_hash.get_secret_value()))

    def authenticate(self, raw_token: str) -> bool:
        """Constant-time verify ``raw_token``."""
        return hmac.compare_digest(token_digest(raw_token, DEVICE_API_DOMAIN), self.token_digest)


def _public_authenticator(request: Request) -> PublicApiAuthenticator:
    cached = getattr(request.app.state, "public_api_authenticator", None)
    if cached is None:
        cached = PublicApiAuthenticator.from_settings(request.app.state.settings)
        request.app.state.public_api_authenticator = cached
    return cached


def _device_authenticator(request: Request) -> DeviceApiAuthenticator:
    cached = getattr(request.app.state, "device_api_authenticator", None)
    if cached is None:
        cached = DeviceApiAuthenticator.from_settings(request.app.state.settings)
        request.app.state.device_api_authenticator = cached
    return cached


async def require_public_client_principal(
    request: Request, credentials: BearerCredentials
) -> ClientPrincipal:
    """Verify the Public API bearer token."""
    if credentials is None:
        raise _unauthorized()
    principal = _public_authenticator(request).authenticate(credentials.credentials)
    if principal is None:
        raise _unauthorized()
    return principal


def require_public_scope(required_scope: str) -> Callable[..., Awaitable[ClientPrincipal]]:
    """Build a dependency that requires ``required_scope`` (401 first, then 403)."""

    async def dependency(
        principal: Annotated[ClientPrincipal, Depends(require_public_client_principal)],
    ) -> ClientPrincipal:
        if required_scope not in principal.scopes:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="FORBIDDEN")
        return principal

    return dependency


async def require_device_bearer_token(request: Request, credentials: BearerCredentials) -> None:
    """Verify the shared Device API bearer token."""
    if credentials is None or not _device_authenticator(request).authenticate(
        credentials.credentials
    ):
        raise _unauthorized()


@dataclass(frozen=True, slots=True)
class UserPrincipal:
    """End user resolved from a phone-app login session."""

    user_id: UUID
    public_user_id: str
    phone_number: str
    display_name: str | None


async def require_user_session(
    credentials: BearerCredentials,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserPrincipal:
    """Verify a phone-app session token by looking up its hash in ``sessions``."""
    if credentials is None:
        raise _unauthorized()
    repo = SessionRepository(session)
    authenticated = await repo.get_valid_by_token_hash(
        hash_token(credentials.credentials), now=datetime.now(UTC)
    )
    if authenticated is None:
        raise _unauthorized()
    user = authenticated.user
    return UserPrincipal(
        user_id=user.id,
        public_user_id=user.public_user_id,
        phone_number=user.phone_number,
        display_name=user.display_name,
    )
