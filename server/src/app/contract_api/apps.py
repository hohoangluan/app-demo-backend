"""Factories for isolated Public and Device contract applications."""

from fastapi import Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import __version__
from app.contract_api.base import ContractFastAPI
from app.contract_api.device import router as device_router
from app.contract_api.public import router as public_router
from app.schemas.common import ErrorResponse, PublicError


def create_public_contract_app() -> ContractFastAPI:
    """Create the Public-only contract application."""
    application = ContractFastAPI(
        title="App Communication Server Public API",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _request: Request,
        _error: RequestValidationError,
    ) -> JSONResponse:
        payload = ErrorResponse(
            error=PublicError(
                code="INVALID_REQUEST",
                message="Invalid request",
                details={},
            )
        )
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content=payload.model_dump(mode="json"),
        )

    application.include_router(public_router)
    return application


def create_device_contract_app() -> ContractFastAPI:
    """Create the Internal Device-only contract application."""
    application = ContractFastAPI(
        title="App Communication Server Device API",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.include_router(device_router)
    return application
