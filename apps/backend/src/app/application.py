"""FastAPI application factory."""

from fastapi import FastAPI

from app import __version__
from app.api.health import router as health_router
from app.config import Settings


def create_app(settings: Settings) -> FastAPI:
    """Create an application with validated settings and registered routers."""
    application = FastAPI(
        title="App Communication Server",
        version=__version__,
    )
    application.state.settings = settings

    # P1 changes this only after PostgreSQL and managed workers finish bootstrapping.
    application.state.ready = False
    application.include_router(health_router)
    return application
