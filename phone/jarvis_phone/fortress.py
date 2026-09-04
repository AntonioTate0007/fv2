"""Client for the Fortress trading backend (server/main.py) so Jarvis can report
on the account from the phone. Everything fails soft with a readable message."""

from __future__ import annotations

from typing import Any, Optional

import httpx

from .config import settings


def _money(n: Any) -> str:
    try:
        return "${:,.0f}".format(float(n))
    except (TypeError, ValueError):
        return "—"


class Fortress:
    def __init__(self, url: str | None = None, token: str | None = None):
        self.url = (url if url is not None else settings.fortress_url).rstrip("/")
        self.token = token if token is not None else settings.fortress_token

    @property
    def configured(self) -> bool:
        return bool(self.url)

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    async def _get(self, path: str, **params) -> Any:
        async with httpx.AsyncClient(timeout=30.0) as c:
            r = await c.get(f"{self.url}{path}", headers=self._headers(), params=params)
            r.raise_for_status()
            return r.json()

    async def _post(self, path: str, body: dict) -> Any:
        async with httpx.AsyncClient(timeout=120.0) as c:
            r = await c.post(f"{self.url}{path}", headers=self._headers(), json=body)
            r.raise_for_status()
            return r.json()

    # ── formatted reports ─────────────────────────────────────────────────
    async def status_text(self) -> str:
        if not self.configured:
            return "Fortress isn't linked — set FORTRESS_URL and FORTRESS_API_TOKEN."
        try:
            s = await self._get("/v1/agents/status")
        except Exception as e:
            return f"Couldn't reach Fortress at {self.url}: {_err(e)}"
        parts = [f"Fortress · {s.get('mode', '?')}"]
        acct = s.get("account")
        if acct:
            equity = float(acct.get("equity") or 0)
            pnl = equity - float(acct.get("lastEquity") or 0)
            parts.append(f"Equity {_money(equity)} ({'+' if pnl >= 0 else ''}{_money(pnl)} "
                         f"today), buying power {_money(acct.get('buyingPower'))}")
        else:
            parts.append("No live account (mock mode)")
        d: Optional[dict] = s.get("lastDecision") or s.get("last_decision")
        if d:
            parts.append(f"Last swarm decision: {d.get('posture', '?')}, risk gate "
                         f"{'open' if d.get('riskGateOpen') else 'closed'} — "
                         f"{d.get('headline', '')}")
        else:
            parts.append("No swarm cycle run yet")
        return ". ".join(parts) + "."

    async def positions_text(self) -> str:
        if not self.configured:
            return "Fortress isn't linked — set FORTRESS_URL and FORTRESS_API_TOKEN."
        try:
            positions = await self._get("/v1/armory/positions")
        except Exception as e:
            return f"Couldn't fetch positions: {_err(e)}"
        if not positions:
            return "Flat — no open positions."
        lines = [f"{len(positions)} open position{'s' if len(positions) != 1 else ''}:"]
        for p in positions[:15]:
            lines.append(f"• {p.get('ticker')} {p.get('strategyLabel', '')} "
                         f"×{p.get('contracts', 1)} (entry {_money(p.get('entryPremium'))} "
                         f"→ now {_money(p.get('currentPremium'))})")
        return "\n".join(lines)

    async def run_text(self, capital: int = 1000) -> str:
        if not self.configured:
            return "Fortress isn't linked — set FORTRESS_URL and FORTRESS_API_TOKEN."
        try:
            d = await self._post("/v1/agents/run", {"capital": max(100, int(capital))})
        except Exception as e:
            return f"Swarm run failed: {_err(e)}"
        actions = d.get("actions") or []
        gate = "open" if d.get("riskGateOpen") else "closed"
        lines = [f"Swarm says {d.get('posture', '?')}, risk gate {gate} — "
                 f"{d.get('headline', '')}"]
        for a in actions[:5]:
            strat = a.get("strategy") or ""
            cap = a.get("suggestedCapital")
            lines.append(f"• {a.get('action', '?')} {a.get('ticker', '')} {strat}"
                         f"{' ' + _money(cap) if cap else ''} — {a.get('rationale', '')}")
        if not actions:
            lines.append("No actions recommended this cycle.")
        return "\n".join(lines)


def _err(e: Exception) -> str:
    if isinstance(e, httpx.HTTPStatusError):
        return f"HTTP {e.response.status_code}"
    return e.__class__.__name__
