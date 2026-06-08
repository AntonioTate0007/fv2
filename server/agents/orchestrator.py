"""
MainOrchestrator — the main agent.

It owns the cycle: build one market context, fan it out to the three sub-agents,
collect their `AgentReport`s, then *fuse* them into a single consolidated decision.

Fusion rules (deliberately simple and auditable):
  1. Auto Hedge is the gate. If it says we're over the risk ceiling, NO new
     positions open this cycle — only its hedge actions are actionable.
  2. Vibe Trading proposes the ideas. Each idea must be *confirmed* by Fincept's
     regime read (a bullish idea needs a non-RISK_OFF tape; bearish needs non-RISK_ON).
  3. Confirmed ideas are ranked by combined conviction; the top ones become the
     orchestrator's recommended actions, sized within the risk budget.

Every consolidated decision carries the three source report hashes, so the whole
chain is verifiable end-to-end.
"""

from __future__ import annotations

import threading
import time
from typing import Optional

from pydantic import BaseModel, Field

from .auto_hedge import AutoHedgeAgent
from .base import AgentReport, AgentStatus, Signal, SignalDirection
from .context import SwarmContext, build_context
from .fincept import FinceptAgent
from .vibe_trading import VibeTradingAgent


class RecommendedAction(BaseModel):
    ticker: str
    action: str                 # OPEN / HEDGE / CLOSE
    direction: SignalDirection
    strategy: Optional[str]
    conviction: float
    rationale: str
    suggestedCapital: Optional[int] = None
    sources: list[str] = Field(default_factory=list)  # contributing agent names


class ConsolidatedDecision(BaseModel):
    cycleId: str
    generatedAt: float
    mode: str                   # MOCK / PAPER / LIVE
    posture: str                # AGGRESSIVE / NEUTRAL / DEFENSIVE
    riskGateOpen: bool          # may we open new positions?
    headline: str
    actions: list[RecommendedAction] = Field(default_factory=list)
    reports: list[AgentReport] = Field(default_factory=list)
    reportHashes: dict[str, str] = Field(default_factory=dict)
    cycleDurationMs: int = 0


class MainOrchestrator:
    def __init__(self) -> None:
        self.auto_hedge = AutoHedgeAgent()
        self.vibe = VibeTradingAgent()
        self.fincept = FinceptAgent()
        self._lock = threading.Lock()
        self._cycles = 0
        self.last_decision: Optional[ConsolidatedDecision] = None

    # ── Public API ───────────────────────────────────────────────────────────────
    def run_cycle(self, capital: int = 1000) -> ConsolidatedDecision:
        """One full orchestration pass. Thread-safe; serialised so concurrent
        callers (API + background loop) don't interleave a cycle."""
        with self._lock:
            start = time.time()
            ctx = build_context(capital)

            hedge_report = self.auto_hedge.run(ctx)
            vibe_report = self.vibe.run(ctx)
            fincept_report = self.fincept.run(ctx)
            reports = [hedge_report, vibe_report, fincept_report]

            decision = self._fuse(ctx, hedge_report, vibe_report, fincept_report)
            decision.cycleDurationMs = int((time.time() - start) * 1000)
            self._cycles += 1
            self.last_decision = decision
            return decision

    def status(self) -> dict:
        """Lightweight state for the dashboard's top bar / polling."""
        return {
            "cycles": self._cycles,
            "agents": [
                self._agent_state(self.auto_hedge.last_report, "Auto Hedge"),
                self._agent_state(self.vibe.last_report, "Vibe Trading"),
                self._agent_state(self.fincept.last_report, "Fincept Terminal"),
            ],
            "lastDecision": self.last_decision.model_dump() if self.last_decision else None,
        }

    @staticmethod
    def _agent_state(report: Optional[AgentReport], title: str) -> dict:
        if report is None:
            return {"title": title, "status": AgentStatus.IDLE.value, "summary": "Not run yet."}
        return {
            "title": title,
            "agent": report.agent,
            "status": report.status.value,
            "summary": report.summary,
            "signals": len(report.signals),
            "generatedAt": report.generatedAt,
            "contentHash": report.contentHash,
        }

    # ── Fusion ───────────────────────────────────────────────────────────────────
    def _fuse(self, ctx: SwarmContext, hedge: AgentReport,
              vibe: AgentReport, fincept: AgentReport) -> ConsolidatedDecision:
        regime = str(fincept.metrics.get("regime", "MIXED"))
        risk_gate_open = not bool(hedge.metrics.get("overRiskCeiling", False))

        actions: list[RecommendedAction] = []

        # 1. Hedge actions are always actionable and take priority.
        for s in hedge.signals:
            action = "CLOSE" if s.strategy == "CLOSE_PROFIT_TARGET" else "HEDGE"
            actions.append(RecommendedAction(
                ticker=s.ticker, action=action, direction=s.direction,
                strategy=s.strategy, conviction=s.conviction, rationale=s.rationale,
                sources=["auto_hedge"],
            ))

        # 2. New-position ideas from Vibe, confirmed by Fincept's regime.
        if risk_gate_open:
            fincept_by_ticker = {
                s.ticker: s for s in fincept.signals
            }
            for s in vibe.signals:
                if not self._regime_confirms(s.direction, regime):
                    continue
                sources = ["vibe_trading"]
                conviction = s.conviction
                # Fincept agreeing on the same name bumps conviction.
                tech = fincept_by_ticker.get(s.ticker)
                if tech and tech.direction == s.direction:
                    conviction = round(min(1.0, (s.conviction + tech.conviction) / 2 + 0.1), 2)
                    sources.append("fincept")
                actions.append(RecommendedAction(
                    ticker=s.ticker, action="OPEN", direction=s.direction,
                    strategy=s.strategy, conviction=conviction, rationale=s.rationale,
                    suggestedCapital=s.suggestedCapital or ctx.capital, sources=sources,
                ))

        # Rank: hedges/closes first (they're risk-reducing), then by conviction.
        def _rank(a: RecommendedAction) -> tuple:
            priority = 0 if a.action in ("HEDGE", "CLOSE") else 1
            return (priority, -a.conviction)
        actions.sort(key=_rank)
        actions = actions[:6]

        posture, headline = self._posture(risk_gate_open, regime, actions)

        return ConsolidatedDecision(
            cycleId=f"cycle-{int(time.time())}-{self._cycles + 1}",
            generatedAt=time.time(),
            mode=ctx.mode,
            posture=posture,
            riskGateOpen=risk_gate_open,
            headline=headline,
            actions=actions,
            reports=[hedge, vibe, fincept],
            reportHashes={r.agent: r.contentHash for r in (hedge, vibe, fincept)},
        )

    @staticmethod
    def _regime_confirms(direction: SignalDirection, regime: str) -> bool:
        if direction == SignalDirection.BULLISH:
            return regime != "RISK_OFF"
        if direction == SignalDirection.BEARISH:
            return regime != "RISK_ON"
        return True  # NEUTRAL ideas always allowed

    @staticmethod
    def _posture(gate_open: bool, regime: str, actions: list[RecommendedAction]) -> tuple[str, str]:
        opens = sum(1 for a in actions if a.action == "OPEN")
        hedges = sum(1 for a in actions if a.action in ("HEDGE", "CLOSE"))
        if not gate_open:
            return ("DEFENSIVE",
                    f"Risk gate CLOSED — at exposure ceiling. {hedges} risk-reducing "
                    f"action(s) queued; new positions on hold.")
        if regime == "RISK_ON" and opens >= 2:
            return ("AGGRESSIVE",
                    f"Risk-on tape with {opens} confirmed setup(s). Green to deploy premium.")
        if regime == "RISK_OFF":
            return ("DEFENSIVE",
                    f"Risk-off tape — staying selective. {hedges} hedge(s), {opens} new idea(s).")
        return ("NEUTRAL",
                f"Mixed tape. {opens} confirmed idea(s), {hedges} risk action(s).")


# ── Singleton ─────────────────────────────────────────────────────────────────────

_orchestrator: Optional[MainOrchestrator] = None
_singleton_lock = threading.Lock()


def get_orchestrator() -> MainOrchestrator:
    global _orchestrator
    if _orchestrator is None:
        with _singleton_lock:
            if _orchestrator is None:
                _orchestrator = MainOrchestrator()
    return _orchestrator
