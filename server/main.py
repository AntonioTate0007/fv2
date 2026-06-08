"""
Fortress backend — FastAPI service that the Android app talks to.

Routes (all under /v1, all gated by Authorization: Bearer <FORTRESS_API_TOKEN>):
    GET  /v1/radar/scan?capital=N           → List[ScannedTrade]
    POST /v1/radar/deploy                   → DeployResponse
    GET  /v1/armory/positions               → List[ActivePosition]
    POST /v1/armory/close                   → CloseResponse
    POST /v1/officer/ask                    → RiskOfficerResponse
    POST /v1/notifications/register         → {ok: true}
    GET  /v1/agents/status                  → swarm state + last decision
    POST /v1/agents/run                     → run a cycle → ConsolidatedDecision
    GET  /v1/agents/{name}/report           → one sub-agent's latest report

Public:
    GET  /                                  → service status
    GET  /dashboard                         → agent-swarm web dashboard (HTML)

Admin (gated by Authorization: Bearer <ADMIN_TOKEN>):
    POST /admin/test-alert                  → fires a profit-target FCM push
    GET  /admin/tokens                      → list registered FCM tokens

Env vars:
    FORTRESS_API_TOKEN              Bearer required on every /v1/* route. REQUIRED.
    ALPACA_API_KEY / _SECRET        Live broker. When unset, /v1/radar/* and
                                    /v1/armory/* fall back to mock data.
    ALPACA_PAPER                    Defaults "true". Set "false" only when you
                                    actively want real-money orders.
    GEMINI_API_KEY                  Real Risk Officer replies; otherwise stubbed.
    FIREBASE_CREDENTIALS_JSON       Firebase Admin service-account JSON, enables
                                    push notifications (currently unused — app
                                    polls via WorkManager instead).
    ADMIN_TOKEN                     Bearer for /admin/* routes.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import random
import secrets
import uuid
from contextlib import asynccontextmanager
from datetime import date, timedelta
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException, Header, Query, status
from fastapi.responses import FileResponse
from pydantic import BaseModel

import alpaca as broker  # type: ignore   # sibling module, both under server/
from agents import get_orchestrator  # type: ignore   # agents/ package under server/

try:
    import google.generativeai as genai
    HAS_GEMINI = True
except Exception:
    HAS_GEMINI = False

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
    HAS_FIREBASE = True
except Exception:
    HAS_FIREBASE = False


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger("fortress")


# ── In-memory state (swap for Postgres / Redis if you add multi-user / persistence) ─

FCM_TOKENS: set[str] = set()


# ── Models ──────────────────────────────────────────────────────────────────────

class ScannedTrade(BaseModel):
    id: str
    ticker: str
    strategy: str
    shortStrike: float
    longStrike: float
    expiration: str
    dte: int
    estimatedCreditPerContract: float
    safetyBufferPct: float
    underlyingPrice: float
    probabilityOfProfit: float
    ivRank: float
    earningsClear: bool


class DeployRequest(BaseModel):
    tradeId: str
    capitalDeployment: int
    biometricToken: str
    # Optional full trade payload — when present, the server uses it directly so
    # the broker doesn't need a fresh chain lookup. The client sends this for
    # idempotency: deploy what the user actually saw on the radar card.
    trade: Optional[ScannedTrade] = None


class DeployResponse(BaseModel):
    success: bool
    orderId: Optional[str] = None
    message: Optional[str] = None


class ActivePosition(BaseModel):
    id: str
    ticker: str
    strategyLabel: str
    shortStrike: float
    underlyingPrice: float
    entryPremium: float
    currentPremium: float
    expiration: str
    contracts: int


class CloseRequest(BaseModel):
    positionId: str
    biometricToken: str


class CloseResponse(BaseModel):
    success: bool
    message: Optional[str] = None


class RiskOfficerRequest(BaseModel):
    prompt: str
    capitalContext: Optional[int] = None
    imageBase64: Optional[str] = None


class RiskOfficerResponse(BaseModel):
    reply: str


class FcmTokenRequest(BaseModel):
    token: str


class AgentRunRequest(BaseModel):
    capital: int = 1000


# ── Auth ────────────────────────────────────────────────────────────────────────

def _bearer_from_header(authorization: Optional[str]) -> Optional[str]:
    if not authorization:
        return None
    if authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def require_app_auth(authorization: Optional[str] = Header(default=None)) -> None:
    """Bearer auth for every /v1/* route used by the Android client."""
    expected = os.getenv("FORTRESS_API_TOKEN")
    if not expected:
        # Hard-fail on misconfiguration in prod, but allow local dev when there's
        # no token set AND we're clearly not in production (no live broker).
        if broker.is_configured() and broker.is_live():
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                                "FORTRESS_API_TOKEN not set on server")
        return
    given = _bearer_from_header(authorization)
    if not given or not secrets.compare_digest(given, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "bad bearer token")


def require_admin_auth(authorization: Optional[str] = Header(default=None)) -> None:
    expected = os.getenv("ADMIN_TOKEN")
    if not expected:
        return
    given = _bearer_from_header(authorization)
    if not given or not secrets.compare_digest(given, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "bad admin token")


# ── Firebase / Gemini lifecycle ─────────────────────────────────────────────────

def init_firebase() -> bool:
    if not HAS_FIREBASE:
        return False
    if firebase_admin._apps:
        return True
    raw = os.getenv("FIREBASE_CREDENTIALS_JSON")
    if not raw:
        return False
    try:
        cred = credentials.Certificate(json.loads(raw))
        firebase_admin.initialize_app(cred)
        return True
    except Exception as e:
        log.exception("[firebase] init failed: %s", e)
        return False


def init_gemini() -> bool:
    if not HAS_GEMINI:
        return False
    key = os.getenv("GEMINI_API_KEY")
    if not key:
        return False
    genai.configure(api_key=key)
    return True


@asynccontextmanager
async def lifespan(_app: FastAPI):
    fb = init_firebase()
    gm = init_gemini()
    al = broker.is_configured()
    mode = "LIVE" if (al and broker.is_live()) else ("PAPER" if al else "MOCK")
    log.info("[startup] alpaca=%s firebase=%s gemini=%s",
             mode, "on" if fb else "off", "on" if gm else "off")
    yield


app = FastAPI(title="Fortress API", version="7.0", lifespan=lifespan)


# ── Mock data (dev fallback when ALPACA_* env vars aren't set) ──────────────────

def _next_friday(weeks: int = 1) -> str:
    today = date.today()
    days_until_friday = (4 - today.weekday()) % 7 or 7
    target = today + timedelta(days=days_until_friday + 7 * (weeks - 1))
    return target.isoformat()


def mock_scan() -> list[dict]:
    def jc(b: float) -> float: return max(0.35, min(0.80, b + random.uniform(-0.05, 0.05)))
    def ji(b: float) -> float: return max(0.10, min(0.99, b + random.uniform(-0.04, 0.04)))
    near, far = _next_friday(1), _next_friday(2)
    return [
        dict(id="AAPL-PCS-265", ticker="AAPL", strategy="PUT_CREDIT_SPREAD",
             shortStrike=265.0, longStrike=260.0, expiration=near, dte=10,
             estimatedCreditPerContract=jc(0.78), safetyBufferPct=0.082,
             underlyingPrice=288.42, probabilityOfProfit=0.86, ivRank=ji(0.68),
             earningsClear=True),
        dict(id="MSFT-PCS-410", ticker="MSFT", strategy="PUT_CREDIT_SPREAD",
             shortStrike=410.0, longStrike=400.0, expiration=near, dte=10,
             estimatedCreditPerContract=jc(0.67), safetyBufferPct=0.067,
             underlyingPrice=439.10, probabilityOfProfit=0.82, ivRank=ji(0.74),
             earningsClear=True),
        dict(id="NVDA-CSP-115", ticker="NVDA", strategy="CASH_SECURED_PUT",
             shortStrike=115.0, longStrike=115.0, expiration=far, dte=14,
             estimatedCreditPerContract=jc(0.54), safetyBufferPct=0.094,
             underlyingPrice=127.05, probabilityOfProfit=0.79, ivRank=ji(0.81),
             earningsClear=True),
    ]


def mock_positions() -> list[dict]:
    near, far = _next_friday(1), _next_friday(2)
    return [
        dict(id="POS-AAPL-1", ticker="AAPL", strategyLabel="$265 / $260 Put Spread",
             shortStrike=265.0, underlyingPrice=286.10, entryPremium=78.0,
             currentPremium=38.0, expiration=near, contracts=4),
        dict(id="POS-MSFT-1", ticker="MSFT", strategyLabel="$410 / $400 Put Spread",
             shortStrike=410.0, underlyingPrice=432.55, entryPremium=134.0,
             currentPremium=71.0, expiration=near, contracts=2),
        dict(id="POS-NVDA-1", ticker="NVDA", strategyLabel="$115 Cash-Secured Put",
             shortStrike=115.0, underlyingPrice=124.60, entryPremium=108.0,
             currentPremium=61.0, expiration=far, contracts=1),
    ]


# ── Risk Officer (Gemini) ───────────────────────────────────────────────────────

SYSTEM_INSTRUCTION = """You are the Fortress Risk Officer — an AI specialist in selling
options premium. The Fortress strategy is:

  • Sell put credit spreads, call credit spreads, or cash-secured puts
  • DTE between 7 and 14 days (theta sweet spot)
  • IV rank ≥ 50 (fat premium)
  • Short strike ≥ 5% out of the money (safety buffer / "moat")
  • No earnings inside the trade window
  • Profit-take at 50% of credit collected
  • Capital blocks of $500 / $1000 / $2500 / $5000

Be terse, decisive, and numerate. Speak in dollar terms, not abstractions. When the
user asks about a play, run it through the filter stack and give a green/yellow/red
verdict with one-line reasoning per filter."""


def gemini_officer_reply(req: RiskOfficerRequest) -> str:
    if not init_gemini():
        return ("Officer is offline — set GEMINI_API_KEY on the server, or paste your "
                "key in the app's Settings screen to talk to Gemini directly.")
    model = genai.GenerativeModel("gemini-1.5-pro", system_instruction=SYSTEM_INSTRUCTION)
    parts: list = []
    if req.capitalContext is not None:
        parts.append(f"(Sizing context: ${req.capitalContext} block)\n")
    parts.append(req.prompt)
    if req.imageBase64:
        try:
            parts.append({"mime_type": "image/jpeg", "data": base64.b64decode(req.imageBase64)})
        except Exception:
            pass
    resp = model.generate_content(parts)
    return resp.text or "I couldn't generate a reply."


# ── Routes ──────────────────────────────────────────────────────────────────────

@app.get("/")
def root():
    """Public status — no auth, just so deploy health-checks pass."""
    al = broker.is_configured()
    return {
        "service": "fortress-api",
        "version": "7.0",
        "alpaca": ("LIVE" if al and broker.is_live() else
                   "PAPER" if al else "MOCK"),
        "gemini": init_gemini(),
        "firebase": init_firebase(),
        "tokens_registered": len(FCM_TOKENS),
    }


@app.get("/v1/radar/scan",
         response_model=List[ScannedTrade],
         dependencies=[Depends(require_app_auth)])
def scan(capital: int = Query(..., ge=100)):
    if broker.is_configured():
        live = broker.scan_chains(capital)
        if live:
            return live
        log.info("[scan] broker returned empty — falling through to mock data")
    return mock_scan()


@app.post("/v1/radar/deploy",
          response_model=DeployResponse,
          dependencies=[Depends(require_app_auth)])
def deploy(req: DeployRequest):
    if not req.biometricToken:
        return DeployResponse(success=False, message="biometric token missing")

    if broker.is_configured():
        if req.trade is None:
            return DeployResponse(success=False,
                                  message="trade payload required when broker is live")
        ok, order_id, msg = broker.submit_spread(req.trade.dict(), req.capitalDeployment)
        return DeployResponse(success=ok, orderId=order_id, message=msg)

    # Dev/mock fallback.
    return DeployResponse(success=True, orderId=f"MOCK-{uuid.uuid4()}",
                          message="(mock mode — no broker configured)")


@app.get("/v1/armory/positions",
         response_model=List[ActivePosition],
         dependencies=[Depends(require_app_auth)])
def positions():
    if broker.is_configured():
        return broker.list_positions()
    return mock_positions()


@app.post("/v1/armory/close",
          response_model=CloseResponse,
          dependencies=[Depends(require_app_auth)])
def close_position(req: CloseRequest):
    if not req.biometricToken:
        return CloseResponse(success=False, message="biometric token missing")
    if broker.is_configured():
        ok, msg = broker.close_position(req.positionId)
        return CloseResponse(success=ok, message=msg)
    return CloseResponse(success=True, message="(mock) Position closed at market.")


@app.post("/v1/officer/ask",
          response_model=RiskOfficerResponse,
          dependencies=[Depends(require_app_auth)])
def officer_ask(req: RiskOfficerRequest):
    return RiskOfficerResponse(reply=gemini_officer_reply(req))


@app.post("/v1/notifications/register",
          dependencies=[Depends(require_app_auth)])
def register_token(req: FcmTokenRequest):
    if req.token:
        FCM_TOKENS.add(req.token)
    return {"ok": True, "registered": len(FCM_TOKENS)}


# ── Agent swarm ───────────────────────────────────────────────────────────────────
#
# The three sub-agents (Auto Hedge, Vibe Trading, Fincept) report back to the main
# orchestrator. These routes drive a cycle and expose the consolidated decision.

@app.get("/v1/agents/status", dependencies=[Depends(require_app_auth)])
def agents_status():
    """Current state of every agent + the last consolidated decision. Cheap; safe
    to poll from the dashboard."""
    orch = get_orchestrator()
    return {
        "account": broker.get_account(),  # None in mock mode
        "mode": ("LIVE" if broker.is_configured() and broker.is_live()
                 else "PAPER" if broker.is_configured() else "MOCK"),
        **orch.status(),
    }


@app.post("/v1/agents/run", dependencies=[Depends(require_app_auth)])
def agents_run(req: AgentRunRequest):
    """Trigger one orchestration cycle: all three sub-agents analyze the same
    market snapshot and report back; the orchestrator fuses them into a decision."""
    decision = get_orchestrator().run_cycle(capital=req.capital)
    return decision.model_dump()


@app.get("/v1/agents/{name}/report", dependencies=[Depends(require_app_auth)])
def agent_report(name: str):
    """Latest report from a single sub-agent: auto_hedge | vibe_trading | fincept."""
    orch = get_orchestrator()
    mapping = {
        "auto_hedge": orch.auto_hedge,
        "vibe_trading": orch.vibe,
        "fincept": orch.fincept,
    }
    agent = mapping.get(name)
    if agent is None:
        raise HTTPException(404, f"unknown agent '{name}' "
                                 f"(expected one of {list(mapping)})")
    if agent.last_report is None:
        raise HTTPException(409, f"agent '{name}' has not run yet — POST /v1/agents/run first")
    return agent.last_report.model_dump()


# ── Dashboard (static single-page UI served by the API) ─────────────────────────

@app.get("/dashboard", include_in_schema=False)
def dashboard():
    """Serve the swarm dashboard. The page itself prompts for the bearer token
    (or works token-free in local mock mode) and polls the /v1/agents/* routes."""
    here = os.path.dirname(os.path.abspath(__file__))
    return FileResponse(os.path.join(here, "dashboard", "index.html"))


# ── Admin / debug ───────────────────────────────────────────────────────────────

@app.get("/admin/tokens", dependencies=[Depends(require_admin_auth)])
def list_tokens():
    return {"count": len(FCM_TOKENS), "tokens": list(FCM_TOKENS)}


class TestAlertRequest(BaseModel):
    ticker: str = "AAPL"
    positionId: str = "TEST-POS-1"
    profitPct: float = 0.5
    currentPremium: str = "0.39"


@app.post("/admin/test-alert", dependencies=[Depends(require_admin_auth)])
def test_alert(req: TestAlertRequest):
    if not init_firebase():
        raise HTTPException(503, "Firebase Admin not configured")
    if not FCM_TOKENS:
        raise HTTPException(400, "No devices registered")
    msg = messaging.MulticastMessage(
        data={
            "type": "profit_target",
            "positionId": req.positionId,
            "ticker": req.ticker,
            "profitPct": str(req.profitPct),
            "currentPremium": req.currentPremium,
        },
        tokens=list(FCM_TOKENS),
    )
    resp = messaging.send_each_for_multicast(msg)
    for token, result in zip(list(FCM_TOKENS), resp.responses):
        if not result.success:
            err = getattr(result.exception, "code", "")
            if err in ("registration-token-not-registered", "invalid-argument"):
                FCM_TOKENS.discard(token)
    return {"sent": resp.success_count, "failed": resp.failure_count}
