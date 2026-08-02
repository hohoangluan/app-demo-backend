"""Public and Device API Bearer authentication dependencies.

Implements ``docs/p1-api-plan.md`` "Bearer authentication" end to end for
the Public API side (client identity + scopes) and the equivalent, simpler
verification for the Device API side.

Judgment call -- Device-side client identity: the doc's "Bearer
authentication" section (provisioning format, config validation steps 1-5,
the scope table) only describes the Public API client. It never defines a
Device-side client-identity or scope model, and ``architeture.md`` lists
``DEVICE_API_TOKEN_HASH`` as a single shared prototype secret, not a
per-device credential (device-level identity for P1 is the payload's own
``user_id``/``device_id`` fields, validated by the future Device service,
not by Bearer auth). Per the caller's instruction to stay conservative
rather than invent a Device-side scope system, :func:`require_device_bearer_token`
only verifies the shared secret and gates access; it produces no principal
object and requires no scope.

Judgment call -- Device domain-separation string: the doc gives the exact
HMAC message for the Public token (``"app-demo-auth/v1/public-api"``) and
states that "Public và Device token có digest khác ngay cả khi
misconfiguration dùng cùng raw token", implying a differently-keyed HMAC
message for Device. The doc does not spell out that string, so this module
uses ``"app-demo-auth/v1/device-api"``, following the same
``app-demo-auth/v1/<surface>`` naming pattern.

Judgment call -- when the pre-decoded digest is built: the doc requires
decoding the configured digest "một lần ở startup" and never per request.
No router wiring exists yet (that is a later task), so there is no startup
hook to call into. Each :class:`PublicApiAuthenticator`/
:class:`DeviceApiAuthenticator` is instead built lazily on first use and
cached on ``request.app.state`` (mirroring the existing
``getattr(request.app.state, "ready", False)`` idiom in
``app/api/health.py``), so the hex-decode still happens at most once per
running application instance, never once per request.
"""

from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass
from typing import TYPE_CHECKING, Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

if TYPE_CHECKING:
    from collections.abc import Awaitable, Callable

    from app.config import Settings

# Domain-separated HMAC messages, per docs/p1-api-plan.md "Provisioning
# format". Keeping Public and Device digests domain-separated means a
# misconfiguration that reuses the same raw token for both surfaces still
# produces different digests.
_PUBLIC_API_DOMAIN = b"app-demo-auth/v1/public-api"
_DEVICE_API_DOMAIN = b"app-demo-auth/v1/device-api"

_bearer_scheme = HTTPBearer(auto_error=False)


@dataclass(frozen=True, slots=True)
class ClientPrincipal:
    """Authenticated Public API client identity and granted scopes.

    Immutable by construction (frozen, slotted): once produced by
    :func:`require_public_client_principal`, nothing downstream can mutate
    the authenticated identity.
    """

    client_id: str
    scopes: frozenset[str]


def _compute_digest(raw_token: str, domain: bytes) -> bytes:
    """Compute ``HMAC-SHA256(key=UTF-8 raw token, message=domain)``.

    Matches ``docs/p1-api-plan.md``'s exact algorithm. ``raw_token`` is
    never trimmed, lowercased, or logged -- it is treated as a fully opaque
    byte string end to end.
    """
    return hmac.new(raw_token.encode("utf-8"), domain, hashlib.sha256).digest()


def _unauthorized() -> HTTPException:
    """Build the stable 401 response for missing/malformed/invalid Bearer auth.

    Every failure branch (missing header, wrong scheme, empty credential,
    wrong token) raises this exact same exception so no branch reveals more
    than another, per docs/p1-api-plan.md step 5.
    """
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="UNAUTHORIZED",
        headers={"WWW-Authenticate": "Bearer"},
    )


@dataclass(frozen=True, slots=True)
class PublicApiAuthenticator:
    """Pre-decoded Public API credential material.

    ``token_digest`` is decoded from ``Settings.public_api_token_hash``
    exactly once (see :meth:`from_settings`); the request path only ever
    calls :meth:`authenticate`, which performs a single
    ``hmac.compare_digest`` call and never re-decodes the configured value.
    """

    client_id: str
    scopes: frozenset[str]
    token_digest: bytes

    @classmethod
    def from_settings(cls, settings: Settings) -> PublicApiAuthenticator:
        """Build the authenticator once from validated, decoded ``Settings``."""
        return cls(
            client_id=settings.public_api_client_id,
            scopes=settings.public_api_scopes,
            token_digest=bytes.fromhex(settings.public_api_token_hash.get_secret_value()),
        )

    def authenticate(self, raw_token: str) -> ClientPrincipal | None:
        """Constant-time verify ``raw_token`` and produce a principal on success.

        Returns ``None`` (never raises) on a failed comparison; the caller
        maps that to the shared 401 response.
        """
        candidate_digest = _compute_digest(raw_token, _PUBLIC_API_DOMAIN)
        if hmac.compare_digest(candidate_digest, self.token_digest):
            return ClientPrincipal(client_id=self.client_id, scopes=self.scopes)
        return None


@dataclass(frozen=True, slots=True)
class DeviceApiAuthenticator:
    """Pre-decoded Device API credential material (single shared secret, no scopes)."""

    token_digest: bytes

    @classmethod
    def from_settings(cls, settings: Settings) -> DeviceApiAuthenticator:
        """Build the authenticator once from validated, decoded ``Settings``."""
        return cls(token_digest=bytes.fromhex(settings.device_api_token_hash.get_secret_value()))

    def authenticate(self, raw_token: str) -> bool:
        """Constant-time verify ``raw_token`` against the configured digest."""
        candidate_digest = _compute_digest(raw_token, _DEVICE_API_DOMAIN)
        return hmac.compare_digest(candidate_digest, self.token_digest)


def _get_public_api_authenticator(request: Request) -> PublicApiAuthenticator:
    """Return the app-instance-cached :class:`PublicApiAuthenticator`, building it once."""
    cached = getattr(request.app.state, "public_api_authenticator", None)
    if cached is None:
        cached = PublicApiAuthenticator.from_settings(request.app.state.settings)
        request.app.state.public_api_authenticator = cached
    return cached


def _get_device_api_authenticator(request: Request) -> DeviceApiAuthenticator:
    """Return the app-instance-cached :class:`DeviceApiAuthenticator`, building it once."""
    cached = getattr(request.app.state, "device_api_authenticator", None)
    if cached is None:
        cached = DeviceApiAuthenticator.from_settings(request.app.state.settings)
        request.app.state.device_api_authenticator = cached
    return cached


async def require_public_client_principal(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)],
) -> ClientPrincipal:
    """FastAPI dependency: verify the Public API bearer token.

    Implements docs/p1-api-plan.md "Request verification" steps 1-6.
    ``HTTPBearer(auto_error=False)`` already resolves a missing
    ``Authorization`` header, a non-``Bearer`` scheme, or an empty
    credential to ``None`` without raising, so the single
    ``credentials is None`` check below already covers step 1's "missing
    header, scheme khác Bearer, empty credential hoặc malformed header"
    cases; an invalid (well-formed but wrong) token is rejected identically
    by :meth:`PublicApiAuthenticator.authenticate` returning ``None``, so
    every failure path raises the exact same 401 response.
    """
    if credentials is None:
        raise _unauthorized()
    authenticator = _get_public_api_authenticator(request)
    principal = authenticator.authenticate(credentials.credentials)
    if principal is None:
        raise _unauthorized()
    return principal


def require_public_scope(required_scope: str) -> Callable[..., Awaitable[ClientPrincipal]]:
    """Build a dependency requiring ``required_scope`` on the authenticated principal.

    Raises ``403 FORBIDDEN`` without ever calling a repository when the
    scope is missing, per docs/p1-api-plan.md step 7. Depends on
    :func:`require_public_client_principal`, so an unauthenticated caller
    is rejected with 401 before scope is even considered.
    """

    async def dependency(
        principal: Annotated[ClientPrincipal, Depends(require_public_client_principal)],
    ) -> ClientPrincipal:
        if required_scope not in principal.scopes:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="FORBIDDEN")
        return principal

    return dependency


async def require_device_bearer_token(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)],
) -> None:
    """FastAPI dependency: verify the shared Device API bearer token.

    Mirrors :func:`require_public_client_principal`'s 401 handling exactly,
    but -- per this module's Device-side judgment call above -- produces no
    principal object and requires no scope: it only gates access.
    """
    if credentials is None:
        raise _unauthorized()
    authenticator = _get_device_api_authenticator(request)
    if not authenticator.authenticate(credentials.credentials):
        raise _unauthorized()
