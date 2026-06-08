"""
Shared contracts for the agent swarm.

Every sub-agent emits an `AgentReport`. The orchestrator consumes those reports —
nothing else. Keeping the wire-shape uniform is what makes the swarm "verifiable":
each report carries a content hash so the orchestrator (and the dashboard) can
prove a report wasn't altered between emission and consumption.
"""

from __future__ import annotations

import hashlib
import json
import time
from enum import Enum
from typing import Any, List, Optional

from pydantic import BaseModel, Field


class AgentStatus(str, Enum):
    OK = "OK"            # ran cleanly, has findings
    DEGRADED = "DEGRADED"  # ran but on fallback/mock data
    ERROR = "ERROR"      # blew up; see `error`
    IDLE = "IDLE"        # never run yet


class SignalDirection(str, Enum):
    BULLISH = "BULLISH"
    BEARISH = "BEARISH"
    NEUTRAL = "NEUTRAL"
    HEDGE = "HEDGE"      # a protective/risk-reducing action


class Signal(BaseModel):
    """One actionable idea produced by an agent."""
    ticker: str
    direction: SignalDirection
    conviction: float = Field(ge=0.0, le=1.0)  # 0..1
    rationale: str
    strategy: Optional[str] = None             # e.g. PUT_CREDIT_SPREAD, BUY_PUT
    suggestedCapital: Optional[int] = None


class AgentReport(BaseModel):
    """The single thing a sub-agent hands back to the main agent."""
    agent: str                       # e.g. "auto_hedge"
    title: str                       # human label, e.g. "Auto Hedge"
    status: AgentStatus
    summary: str                     # one-paragraph headline for the dashboard
    signals: List[Signal] = Field(default_factory=list)
    metrics: dict[str, Any] = Field(default_factory=dict)  # arbitrary numeric/string facts
    error: Optional[str] = None
    generatedAt: float = Field(default_factory=time.time)
    durationMs: int = 0
    contentHash: str = ""            # filled by `finalize()`

    def finalize(self) -> "AgentReport":
        """Stamp a content hash over the meaningful fields. Order-stable."""
        payload = {
            "agent": self.agent,
            "status": self.status.value,
            "summary": self.summary,
            "signals": [s.model_dump() for s in self.signals],
            "metrics": self.metrics,
        }
        blob = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        self.contentHash = hashlib.sha256(blob).hexdigest()[:16]
        return self


class BaseAgent:
    """Tiny base class so every sub-agent runs with the same lifecycle, timing,
    and error containment. Subclasses implement `analyze()` and return an
    AgentReport (without worrying about timing or hashing).
    """

    name: str = "base"
    title: str = "Base Agent"

    def __init__(self) -> None:
        self.last_report: Optional[AgentReport] = None

    # Subclasses override this.
    def analyze(self, context: "SwarmContext") -> AgentReport:  # noqa: F821
        raise NotImplementedError

    def run(self, context: "SwarmContext") -> AgentReport:  # noqa: F821
        start = time.time()
        try:
            report = self.analyze(context)
        except Exception as e:  # never let one agent take down the cycle
            report = AgentReport(
                agent=self.name,
                title=self.title,
                status=AgentStatus.ERROR,
                summary=f"{self.title} failed to run.",
                error=f"{type(e).__name__}: {e}",
            )
        report.durationMs = int((time.time() - start) * 1000)
        self.last_report = report.finalize()
        return self.last_report
