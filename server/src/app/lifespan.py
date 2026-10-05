"""Application lifespan: open the database, run the workers, shut both down cleanly."""

from __future__ import annotations

import logging
from collections.abc import AsyncGenerator  # noqa: TC003
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

from app.database import build_async_engine, build_session_factory
from app.workers.runner import WorkerRunner

if TYPE_CHECKING:
    from fastapi import FastAPI

logger = logging.getLogger("app.lifespan")


@asynccontextmanager
async def application_lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """Lifespan context manager controlling backend startup and shutdown."""
    settings = app.state.settings
    session_factory = getattr(app.state, "session_factory", None)
    engine = None

    if session_factory is None and settings.database_url:
        try:
            engine = build_async_engine(settings.database_url)
            session_factory = build_session_factory(engine)
            app.state.engine = engine
            app.state.session_factory = session_factory
        except Exception:
            logger.exception("Failed to initialize database engine on startup")

    runner: WorkerRunner | None = None
    if session_factory is not None:
        try:
            runner = WorkerRunner(session_factory, settings, app.state.worker_wake_signals)
            app.state.worker_runner = runner
            runner.start()
            app.state.ready = True
            logger.info("Application bootstrap completed successfully")
        except Exception:
            logger.exception("Failed to start worker runner")
            app.state.ready = False
    else:
        app.state.ready = False

    try:
        yield
    finally:
        logger.info("Initiating application shutdown...")
        app.state.ready = False

        if runner is not None:
            await runner.stop()

        if engine is not None:
            await engine.dispose()

        logger.info("Application shutdown completed")
