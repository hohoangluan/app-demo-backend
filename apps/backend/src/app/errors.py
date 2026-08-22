"""Public API domain exceptions and their FastAPI exception-handler mapping.

Implements ``docs/p1-api-plan.md``'s "Exception taxonomy" and "Validation
handler" sections for the real (non-contract) runtime application. Services
raise the typed exceptions defined here instead of ``HTTPException``
(``app/errors.py`` never imports a repository/service, keeping the
dependency direction api -> service -> repository intact); a single set of
FastAPI exception handlers, registered by :func:`register_exception_handlers`
in ``app/application.py``, is the only place a domain error becomes an HTTP
response.

``401``/``403`` are deliberately out of scope here: ``app/auth.py``'s
dependencies already raise ``HTTPException`` directly for those cases (per
that module's own docstring, auth is a router/HTTP-layer concern), and
Starlette's built-in ``HTTPException`` handler already renders them -- this
module does not touch that behavior.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any, Final

from fastapi import status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas.common import ErrorResponse, PublicError

if TYPE_CHECKING:
    from fastapi import FastAPI, Request

logger = logging.getLogger(__name__)

# Mirrors app/contract_api/public.py's PUBLIC_VALIDATION_RESPONSE for the
# real runtime routers: documents the 400 envelope in OpenAPI `responses=`
# so route decorators don't need to duplicate the example inline. This does
# not by itself remove FastAPI's automatic 422 schema entry (only
# ContractFastAPI's `openapi()` override does that, and it is intentionally
# left untouched); actual runtime responses are governed by the exception
# handlers below regardless of what the real app's own OpenAPI documents.
VALIDATION_ERROR_RESPONSES: Final[dict[int | str, dict[str, Any]]] = {
    status.HTTP_400_BAD_REQUEST: {
        "model": ErrorResponse,
        "description": "Invalid request",
        "content": {
            "application/json": {
                "example": {
                    "status": "error",
                    "error": {
                        "code": "INVALID_REQUEST",
                        "message": "Invalid request",
                        "details": {},
                    },
                }
            }
        },
    }
}


class PublicApiError(Exception):
    """Base for typed domain errors mapped to the Public error envelope.

    Subclasses set the class attributes ``status_code`` and ``code``; the
    instance carries the human-readable ``message`` and optional
    ``details``. Services/routers raise these directly -- never
    ``HTTPException`` -- so the mapping to HTTP stays entirely inside
    :func:`register_exception_handlers`.
    """

    status_code: int
    code: str

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        """Store the Public-facing message and optional structured details."""
        super().__init__(message)
        self.message = message
        self.details: dict[str, Any] = details if details is not None else {}


class RequestNotFoundError(PublicApiError):
    """Raised when a ``request_id`` lookup finds nothing owned by the caller.

    Per ``CONTRACT_DECISIONS.md`` ``D-06``, this single exception covers
    both "request does not exist" and "request belongs to another client" --
    both render as ``404 REQUEST_NOT_FOUND``.
    """

    status_code = status.HTTP_404_NOT_FOUND
    code = "REQUEST_NOT_FOUND"


class RequestIdConflictError(PublicApiError):
    """Raised when a ``request_id`` already exists with a different fingerprint/client."""

    status_code = status.HTTP_409_CONFLICT
    code = "REQUEST_ID_CONFLICT"


class PhoneAlreadyRegisteredError(PublicApiError):
    """Raised when registering a phone number that already completed OTP verification."""

    status_code = status.HTTP_409_CONFLICT
    code = "PHONE_ALREADY_REGISTERED"


class InvalidCredentialsError(PublicApiError):
    """Raised when login phone number/password do not match a verified account."""

    status_code = status.HTTP_401_UNAUTHORIZED
    code = "INVALID_CREDENTIALS"


class PhoneNotVerifiedError(PublicApiError):
    """Raised when logging in to an account that never completed OTP verification."""

    status_code = status.HTTP_403_FORBIDDEN
    code = "PHONE_NOT_VERIFIED"


class OtpInvalidError(PublicApiError):
    """Raised when a submitted OTP code does not match the stored hash."""

    status_code = status.HTTP_400_BAD_REQUEST
    code = "OTP_INVALID"


class OtpExpiredError(PublicApiError):
    """Raised when a submitted OTP code has expired or has no pending OTP."""

    status_code = status.HTTP_400_BAD_REQUEST
    code = "OTP_EXPIRED"


class DeviceNotFoundError(PublicApiError):
    """Raised when confirming a device link for a ``device_id`` with no active registration."""

    status_code = status.HTTP_404_NOT_FOUND
    code = "DEVICE_NOT_FOUND"


class DeviceOwnerConflictError(PublicApiError):
    """Raised when confirming a device link for a ``device_id`` owned by another user."""

    status_code = status.HTTP_409_CONFLICT
    code = "DEVICE_OWNER_CONFLICT"


class GlassesDeviceOwnerConflictError(PublicApiError):
    """Raised when linking a glasses ``device_id`` that is actively paired to another user."""

    status_code = status.HTTP_409_CONFLICT
    code = "GLASSES_DEVICE_OWNER_CONFLICT"


class GlassesDeviceNotLinkedError(PublicApiError):
    """Raised when a Public Service API ``device_id`` has no active glasses pairing."""

    status_code = status.HTTP_404_NOT_FOUND
    code = "GLASSES_DEVICE_NOT_LINKED"


class DeviceEventForwardError(PublicApiError):
    """Raised when a spontaneous phone event cannot reach the glasses server."""

    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    code = "DEVICE_EVENT_FORWARD_FAILED"


def _error_response(
    *, status_code: int, code: str, message: str, details: dict[str, Any]
) -> JSONResponse:
    """Build the stable ``{"status": "error", "error": {...}}`` JSON response."""
    envelope = ErrorResponse(error=PublicError(code=code, message=message, details=details))
    return JSONResponse(status_code=status_code, content=envelope.model_dump(mode="json"))


async def _handle_public_api_error(_request: Request, exc: Exception) -> JSONResponse:
    """Map a typed domain error to its stable Public error envelope."""
    if not isinstance(exc, PublicApiError):
        raise exc
    return _error_response(
        status_code=exc.status_code, code=exc.code, message=exc.message, details=exc.details
    )


async def _handle_validation_error(_request: Request, _exc: Exception) -> JSONResponse:
    """Remap FastAPI's default 422 validation failure to ``400 INVALID_REQUEST``.

    Per ``docs/p1-api-plan.md``, exact validation-detail content is blocked
    on ``D-07``; until then, the response only guarantees the stable
    envelope/status/code, never the raw Pydantic error list or input.
    """
    return _error_response(
        status_code=status.HTTP_400_BAD_REQUEST,
        code="INVALID_REQUEST",
        message="Request validation failed",
        details={},
    )


async def _handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    """Log an unhandled exception server-side and return a generic 500 envelope.

    The response body never contains the exception class, message, or
    traceback -- only the stable ``INTERNAL_ERROR`` code and a generic
    message, per ``docs/p1-api-plan.md``'s "Validation handler" step 3.
    """
    logger.error(
        "Unhandled exception while processing %s %s: %s",
        request.method,
        request.url.path,
        exc,
    )

    return _error_response(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code="INTERNAL_ERROR",
        message="Internal server error",
        details={},
    )


def register_exception_handlers(app: FastAPI) -> None:
    """Register the Public exception-handler chain on ``app``.

    Order mirrors ``docs/p1-api-plan.md``'s "Validation handler" section:
    ``RequestValidationError`` first, typed :class:`PublicApiError` domain
    errors second, and any other unhandled ``Exception`` last as the
    catch-all. Starlette dispatches by walking the raised exception's MRO
    and picking the most specific registered handler, so this registration
    order does not change precedence -- it only mirrors the doc's reading
    order. The already-registered default ``HTTPException`` handler (used by
    ``app/auth.py``'s 401/403) remains untouched: it is more specific than
    the ``Exception`` handler registered here, so it always wins for
    ``HTTPException``.
    """
    app.add_exception_handler(RequestValidationError, _handle_validation_error)
    app.add_exception_handler(PublicApiError, _handle_public_api_error)
    app.add_exception_handler(Exception, _handle_unexpected_error)
