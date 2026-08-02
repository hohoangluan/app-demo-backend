"""FastAPI application factory."""

from fastapi import FastAPI

from app import __version__
from app.api.device import router as device_router
from app.api.health import router as health_router
from app.api.service import router as service_router
from app.api.status import router as status_router
from app.config import Settings
from app.errors import register_exception_handlers
from app.lifespan import application_lifespan
from app.logging import RequestCorrelationMiddleware, setup_logging


def create_app(settings: Settings) -> FastAPI:
    """Create an application with validated settings and registered routers."""
    setup_logging(settings.log_level)

    application = FastAPI(
        title="App Communication Server",
        version=__version__,
        lifespan=application_lifespan,
    )
    application.state.settings = settings
    application.state.ready = False

    # Middleware
    application.add_middleware(RequestCorrelationMiddleware)

    # Exception handlers
    register_exception_handlers(application)

    # Routers
    application.include_router(health_router)
    application.include_router(status_router)
    application.include_router(service_router)
    application.include_router(device_router)

    return application
