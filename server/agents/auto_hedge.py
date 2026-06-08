"""
AutoHedgeAgent — the risk/hedge orchestrator (inspired by Auto Hedge / ZkVanguard).

It doesn't hunt for new trades; it watches what's already on and asks "are we too
exposed, and if so, what's the cheapest way to bleed off risk?" It produces HEDGE
signals plus portfolio-level risk metrics that the main agent uses to throttle the
other agents' appetite for new positions.
"""

from __future__ import annotations

from .base import AgentReport, AgentStatus, BaseAgent, Signal, SignalDirection
from .context import SwarmContext

# Portfolio risk thresholds. Conservative by design — this agent's job is to be
# the pessimist in the room.
MAX_CAPITAL_AT_RISK_PCT = 0.40   # flag when >40% of equity is tied up in shorts
DANGER_BUFFER_PCT = 0.04         # short strike within 4% of spot = under pressure
PROFIT_TAKE_PCT = 0.50           # Fortress rule: close at 50% of credit captured


class AutoHedgeAgent(BaseAgent):
    name = "auto_hedge"
    title = "Auto Hedge"

    def analyze(self, ctx: SwarmContext) -> AgentReport:
        positions = ctx.positions
        equity = float((ctx.account or {}).get("equity", 0.0)) or 1.0

        signals: list[Signal] = []
        capital_at_risk = 0.0
        pressured = 0
        take_profit = 0

        for p in positions:
            ticker = p.get("ticker", "?")
            short_strike = float(p.get("shortStrike", 0) or 0)
            spot = float(p.get("underlyingPrice", 0) or 0)
            contracts = int(p.get("contracts", 1) or 1)
            entry = float(p.get("entryPremium", 0) or 0)
            current = float(p.get("currentPremium", 0) or 0)

            # Capital at risk ≈ max loss on the position. A defined-risk credit
            # spread can only lose its width (≈$5 wide on the Fortress desk); a
            # cash-secured put must stand behind the full strike notional.
            label = str(p.get("strategyLabel", "")).lower()
            if "spread" in label:
                capital_at_risk += 5.0 * 100 * contracts      # width × 100 × contracts
            else:
                capital_at_risk += short_strike * 100 * contracts

            # Buffer = how far OTM the short strike still is.
            buffer = (spot - short_strike) / spot if spot > 0 else 1.0
            if 0 <= buffer < DANGER_BUFFER_PCT:
                pressured += 1
                signals.append(Signal(
                    ticker=ticker,
                    direction=SignalDirection.HEDGE,
                    conviction=round(min(1.0, 0.6 + (DANGER_BUFFER_PCT - buffer) * 8), 2),
                    rationale=(f"{ticker} spot ${spot:.2f} is only {buffer*100:.1f}% "
                               f"above the ${short_strike:.0f} short strike — roll down "
                               f"or buy a protective put."),
                    strategy="ROLL_OR_PROTECT",
                ))

            # Profit-take rule: if we've captured ≥50% of the credit, harvest it.
            if entry > 0 and current >= 0:
                captured = (entry - current) / entry
                if captured >= PROFIT_TAKE_PCT:
                    take_profit += 1
                    signals.append(Signal(
                        ticker=ticker,
                        direction=SignalDirection.HEDGE,
                        conviction=round(min(1.0, captured), 2),
                        rationale=(f"{ticker} has captured {captured*100:.0f}% of credit "
                                   f"(${entry:.0f}→${current:.0f}) — close to lock the win."),
                        strategy="CLOSE_PROFIT_TARGET",
                    ))

        risk_pct = capital_at_risk / equity if equity > 0 else 0.0
        over_risk = risk_pct > MAX_CAPITAL_AT_RISK_PCT

        if over_risk:
            signals.insert(0, Signal(
                ticker="PORTFOLIO",
                direction=SignalDirection.HEDGE,
                conviction=round(min(1.0, risk_pct), 2),
                rationale=(f"Capital at risk is {risk_pct*100:.0f}% of equity "
                           f"(> {MAX_CAPITAL_AT_RISK_PCT*100:.0f}% ceiling). Stop opening "
                           f"new positions until exposure comes down."),
                strategy="REDUCE_EXPOSURE",
            ))

        if not positions:
            summary = "No open positions — portfolio risk is flat. Clear to deploy."
            status = AgentStatus.OK if ctx.live else AgentStatus.DEGRADED
        else:
            summary = (f"{len(positions)} positions, {risk_pct*100:.0f}% of equity at risk. "
                       f"{pressured} under strike pressure, {take_profit} at profit target.")
            status = AgentStatus.OK if ctx.live else AgentStatus.DEGRADED

        return AgentReport(
            agent=self.name,
            title=self.title,
            status=status,
            summary=summary,
            signals=signals,
            metrics={
                "openPositions": len(positions),
                "capitalAtRisk": round(capital_at_risk, 2),
                "riskPct": round(risk_pct, 4),
                "overRiskCeiling": over_risk,
                "positionsUnderPressure": pressured,
                "positionsAtProfitTarget": take_profit,
                "riskCeilingPct": MAX_CAPITAL_AT_RISK_PCT,
            },
        )
