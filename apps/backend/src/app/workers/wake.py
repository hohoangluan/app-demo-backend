"""In-process wake signals for latency-sensitive background workers.

Polling remains the durable recovery path.  These events only remove the
avoidable poll delay while the single-process application is running.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

from fastapi import Request


@dataclass(slots=True)
class WorkerWakeSignals:
    """Events used to wake delivery and callback workers after a commit."""

    delivery: asyncio.Event = field(default_factory=asyncio.Event)
    callback: asyncio.Event = field(default_factory=asyncio.Event)


def get_worker_wake_signals(request: Request) -> WorkerWakeSignals:
    """Return the application-scoped worker wake signals."""
    signals: WorkerWakeSignals | None = getattr(request.app.state, "worker_wake_signals", None)
    if signals is None:
        signals = WorkerWakeSignals()
        request.app.state.worker_wake_signals = signals
    return signals
