"""Typed domain errors and the handlers that render them as the Public error envelope.

Services raise :class:`PublicApiError` subclasses; only the handlers here turn
them into HTTP responses. Auth failures (401/403) stay plain ``HTTPException``.
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

# OpenAPI description of the 400 validation envelope for runtime routers.
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
    """Base error: subclasses fix ``status_code`` and ``code``; instances carry message/details."""

    status_code: int
    code: str

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        """Store the Public-facing message and optional structured details."""
        super().__init__(message)
        self.message = message
        self.details: dict[str, Any] = details if details is not None else {}


class RequestNotFoundError(PublicApiError):
    """Raised when a ``request_id`` does not exist or belongs to another caller."""

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
    """Map FastAPI's 422 to ``400 INVALID_REQUEST`` without echoing input or error details."""
    return _error_response(
        status_code=status.HTTP_400_BAD_REQUEST,
        code="INVALID_REQUEST",
        message="Request validation failed",
        details={},
    )


async def _handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    """Log the exception and return a generic ``500 INTERNAL_ERROR`` envelope."""
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
    """Register validation, domain and catch-all handlers (``HTTPException`` keeps its own)."""
    app.add_exception_handler(RequestValidationError, _handle_validation_error)
    app.add_exception_handler(PublicApiError, _handle_public_api_error)
    app.add_exception_handler(Exception, _handle_unexpected_error)
