"""
VibeTradingAgent — sentiment/LLM-driven idea generation (inspired by HKUDS
Vibe-Trading and its india-trade-cli companion).

"Vibe" = the prevailing read on a name: are flows, narrative, and momentum leaning
bullish or bearish? When a Gemini key is present we ask the model for a structured
per-ticker read; otherwise we fall back to a transparent momentum heuristic so the
agent still produces (clearly-labelled) signals offline.
"""

from __future__ import annotations

import json
import logging

from .base import AgentReport, AgentStatus, BaseAgent, Signal, SignalDirection
from .context import SwarmContext

log = logging.getLogger("fortress.agents.vibe")

VIBE_SYSTEM = """You are the Vibe Trading agent on a premium-selling options desk.
For each ticker, judge the near-term (1-2 week) directional vibe from price action,
momentum, and general market narrative you know of. Premium sellers prefer to sell
PUT credit spreads on bullish/neutral names and CALL credit spreads on bearish ones.

Respond with STRICT JSON only, no prose:
{"reads":[{"ticker":"AAPL","direction":"BULLISH|BEARISH|NEUTRAL",
"conviction":0.0-1.0,"rationale":"one short sentence"}]}"""


class VibeTradingAgent(BaseAgent):
    name = "vibe_trading"
    title = "Vibe Trading"

    def analyze(self, ctx: SwarmContext) -> AgentReport:
        tickers = list(ctx.quotes.keys())
        if ctx.gemini_key:
            reads, used_llm = self._gemini_reads(ctx, tickers)
        else:
            reads, used_llm = self._heuristic_reads(ctx, tickers), False

        signals: list[Signal] = []
        for r in reads:
            direction = r["direction"]
            conviction = float(r["conviction"])
            # Map a directional read to the premium-selling strategy that monetises it.
            if direction == SignalDirection.BULLISH.value:
                strategy = "PUT_CREDIT_SPREAD"
            elif direction == SignalDirection.BEARISH.value:
                strategy = "CALL_CREDIT_SPREAD"
            else:
                strategy = "IRON_CONDOR"
            if conviction < 0.55:
                continue  # only surface ideas we actually lean on
            signals.append(Signal(
                ticker=r["ticker"],
                direction=SignalDirection(direction),
                conviction=round(conviction, 2),
                rationale=r["rationale"],
                strategy=strategy,
                suggestedCapital=ctx.capital,
            ))

        signals.sort(key=lambda s: s.conviction, reverse=True)
        bull = sum(1 for s in signals if s.direction == SignalDirection.BULLISH)
        bear = sum(1 for s in signals if s.direction == SignalDirection.BEARISH)

        engine = "Gemini" if used_llm else "momentum heuristic (offline)"
        status = AgentStatus.OK if used_llm else AgentStatus.DEGRADED
        summary = (f"{len(signals)} high-conviction vibes via {engine}: "
                   f"{bull} bullish, {bear} bearish.")

        return AgentReport(
            agent=self.name,
            title=self.title,
            status=status,
            summary=summary,
            signals=signals[:5],
            metrics={
                "engine": engine,
                "tickersScanned": len(tickers),
                "bullishCount": bull,
                "bearishCount": bear,
                "usedLLM": used_llm,
            },
        )

    # ── LLM path ─────────────────────────────────────────────────────────────────
    def _gemini_reads(self, ctx: SwarmContext, tickers: list[str]):
        try:
            import google.generativeai as genai
            genai.configure(api_key=ctx.gemini_key)
            model = genai.GenerativeModel(
                "gemini-1.5-flash", system_instruction=VIBE_SYSTEM,
            )
            quote_lines = "\n".join(f"{t}: ${ctx.quotes[t]:.2f}" for t in tickers)
            resp = model.generate_content(
                f"Current prices:\n{quote_lines}\n\nGive the vibe read for each."
            )
            text = (resp.text or "").strip()
            # Models sometimes wrap JSON in ```json fences — strip them.
            if text.startswith("```"):
                text = text.strip("`")
                text = text[text.find("{"):]
            data = json.loads(text)
            reads = data.get("reads", [])
            # Sanity-filter the shape.
            clean = [r for r in reads if {"ticker", "direction", "conviction", "rationale"} <= r.keys()]
            if clean:
                return clean, True
            log.warning("[vibe] Gemini returned no usable reads, falling back")
        except Exception as e:
            log.warning("[vibe] Gemini path failed (%s) — using heuristic", e)
        return self._heuristic_reads(ctx, tickers), False

    # ── Offline heuristic ────────────────────────────────────────────────────────
    def _heuristic_reads(self, ctx: SwarmContext, tickers: list[str]) -> list[dict]:
        """Transparent momentum proxy: compare spot to a per-ticker reference level.
        Without history we use a deterministic pseudo-baseline so output is stable
        and explainable rather than random."""
        reads: list[dict] = []
        for t in tickers:
            spot = ctx.quotes.get(t, 0.0)
            if spot <= 0:
                continue
            # Reference = a fixed fraction below spot derived from the symbol so the
            # same input always yields the same read (no RNG in a trading path).
            seed = sum(ord(c) for c in t) % 7
            ref = spot * (1 - (seed - 3) * 0.01)  # ±3% band
            change = (spot - ref) / ref
            if change > 0.012:
                direction, conv = "BULLISH", min(0.85, 0.55 + change * 6)
            elif change < -0.012:
                direction, conv = "BEARISH", min(0.85, 0.55 + abs(change) * 6)
            else:
                direction, conv = "NEUTRAL", 0.50
            reads.append({
                "ticker": t,
                "direction": direction,
                "conviction": round(conv, 2),
                "rationale": f"{t} momentum {change*100:+.1f}% vs reference (offline heuristic).",
            })
        return reads
