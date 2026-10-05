"""Start and stop the delivery, timeout and callback worker loops."""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from app.workers.callback import CallbackWorker
from app.workers.delivery import DeliveryWorker
from app.workers.timeout import TimeoutWorker
from app.workers.wake import WorkerWakeSignals

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    from app.config import Settings

logger = logging.getLogger("app.workers.runner")


class WorkerRunner:
    """Manager owning the lifecycle of background worker tasks."""

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        settings: Settings,
        wake_signals: WorkerWakeSignals | None = None,
    ) -> None:
        """Initialize WorkerRunner with session factory and configuration."""
        self._session_factory = session_factory
        self._settings = settings
        self._wake_signals = wake_signals or WorkerWakeSignals()
        self._shutdown_event = asyncio.Event()
        self._tasks: list[asyncio.Task[None]] = []
        self._started = False

    @property
    def is_running(self) -> bool:
        """Return True if background worker tasks are active."""
        return self._started and any(not t.done() for t in self._tasks)

    def start(self) -> None:
        """Spawn background tasks for delivery, timeout, and callback workers."""
        if self._started:
            return

        self._shutdown_event.clear()
        delivery_worker = DeliveryWorker(
            self._session_factory, self._settings, self._wake_signals.delivery
        )
        timeout_worker = TimeoutWorker(self._session_factory, self._settings)
        callback_worker = CallbackWorker(
            self._session_factory, self._settings, self._wake_signals.callback
        )

        self._tasks = [
            asyncio.create_task(
                delivery_worker.run_loop(self._shutdown_event), name="delivery-worker"
            ),
            asyncio.create_task(
                timeout_worker.run_loop(self._shutdown_event), name="timeout-worker"
            ),
            asyncio.create_task(
                callback_worker.run_loop(self._shutdown_event), name="callback-worker"
            ),
        ]
        self._started = True
        logger.info("Started background worker runner with 3 worker loops")

    async def stop(self) -> None:
        """Signal shutdown event and wait gracefully for worker tasks to finish."""
        if not self._started:
            return

        logger.info("Stopping worker runner...")
        self._shutdown_event.set()

        for task in self._tasks:
            if not task.done():
                task.cancel()

        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

        self._tasks.clear()
        self._started = False
        logger.info("Worker runner stopped")
