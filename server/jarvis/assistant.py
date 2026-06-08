"""
Jarvis — the personal trading assistant persona (inspired by OpenJarvis' scheduled
digest agents). It turns the swarm's machine output into something a human wants on
their phone: a short spoken briefing plus the detail in text, over Telegram.

Two jobs:
  • brief(...)        — format a ConsolidatedDecision into (text, spoken) and push it.
  • handle_update(...) — react to an incoming Telegram message (commands + free chat).

Trading-touching commands are gated to the configured owner chat. Without an owner
configured, Jarvis still answers /start and /help but refuses anything sensitive.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

import alpaca as broker  # type: ignore
from agents import get_orchestrator  # type: ignore

from . import telegram

log = logging.getLogger("fortress.jarvis")

JARVIS_PERSONA = """You are Jarvis, a calm, concise personal trading assistant for a
premium-selling options desk (the "Fortress" strategy: sell put/call credit spreads
and cash-secured puts, 7-14 DTE, ≥5% OTM, take profit at 50% of credit). You speak
to your principal over Telegram. Be brief and decisive, talk in dollars and odds,
never hedge with disclaimers. You coordinate three sub-agents — Auto Hedge (risk),
Vibe Trading (sentiment), Fincept (analytics) — that report to a main orchestrator.
When given live context, ground every answer in it."""


# ── Owner gating ─────────────────────────────────────────────────────────────────

def _owner_chat() -> Optional[str]:
    return telegram.default_chat_id()


def _is_owner(chat_id: str) -> bool:
    owner = _owner_chat()
    # If no owner is configured, nobody is trusted for sensitive actions.
    return bool(owner) and str(chat_id) == str(owner)


# ── Mode / account helpers ───────────────────────────────────────────────────────

def current_mode() -> str:
    if not broker.is_configured():
        return "MOCK"
    return "LIVE" if broker.is_live() else "PAPER"


def _money(n) -> str:
    try:
        return "${:,.0f}".format(float(n))
    except (TypeError, ValueError):
        return "—"


# ── Briefing formatting ──────────────────────────────────────────────────────────

def format_brief(decision: dict) -> tuple[str, str]:
    """Return (markdown_text, spoken_text) for a ConsolidatedDecision dict."""
    posture = decision.get("posture", "—")
    gate = "OPEN" if decision.get("riskGateOpen") else "CLOSED"
    mode = decision.get("mode", "—")
    headline = decision.get("headline", "")
    actions = decision.get("actions", []) or []

    lines = [f"*Jarvis briefing* · _{mode}_",
             f"Posture: *{posture}*  ·  risk gate *{gate}*",
             f"_{headline}_"]
    if actions:
        lines.append("")
        for a in actions[:6]:
            conv = int(round(float(a.get("conviction", 0)) * 100))
            strat = a.get("strategy") or ""
            lines.append(f"• *{a['action']}* {a['ticker']} "
                         f"({a.get('direction','')}, {conv}%){f' — {strat}' if strat else ''}")
    else:
        lines.append("\nNo recommended actions this cycle.")

    text = "\n".join(lines)

    # Spoken version: a single flowing paragraph, no markdown/symbols.
    spoken_bits = [f"Briefing. {mode} mode.",
                   f"Posture is {posture}. Risk gate is {gate.lower()}.",
                   headline]
    opens = [a for a in actions if a.get("action") == "OPEN"]
    hedges = [a for a in actions if a.get("action") in ("HEDGE", "CLOSE")]
    if hedges:
        spoken_bits.append(
            f"{len(hedges)} risk action{'s' if len(hedges) != 1 else ''}: " +
            ", ".join(f"{a['action'].lower()} {a['ticker']}" for a in hedges[:3]) + ".")
    if opens:
        spoken_bits.append(
            f"{len(opens)} new idea{'s' if len(opens) != 1 else ''}: " +
            ", ".join(f"{a['ticker']} {a.get('direction','').lower()}" for a in opens[:3]) + ".")
    spoken = " ".join(b for b in spoken_bits if b)
    return text, spoken


async def brief(chat_id: Optional[str], decision: dict, *, voice: bool = True) -> bool:
    """Push a briefing (text + optional voice) to a chat. Defaults to the owner."""
    chat_id = chat_id or _owner_chat()
    if not chat_id:
        return False
    text, spoken = format_brief(decision)
    ok = await telegram.send_text(chat_id, text)
    if voice:
        await telegram.send_voice(chat_id, spoken, caption="🔊 Jarvis briefing")
    return ok


# ── Incoming message handling ────────────────────────────────────────────────────

HELP = (
    "*Jarvis — Fortress trading assistant*\n"
    "/status — account + latest swarm decision\n"
    "/run [capital] — run an agent cycle and brief me\n"
    "/positions — open positions\n"
    "/mode — show paper/live; `/mode paper` or `/mode live confirm` to switch\n"
    "/help — this message\n"
    "_Or just ask me anything about the book or a play._"
)


async def handle_update(update: dict) -> None:
    msg = update.get("message") or update.get("edited_message")
    if not msg:
        return
    chat_id = str(msg.get("chat", {}).get("id", ""))
    text = (msg.get("text") or "").strip()
    if not chat_id or not text:
        return

    cmd, _, arg = text.partition(" ")
    cmd = cmd.lower().lstrip("/")
    arg = arg.strip()

    if cmd in ("start", "help"):
        owner_note = ("" if _owner_chat() else
                      f"\n\n_Your chat id is_ `{chat_id}` — set `TELEGRAM_CHAT_ID` to "
                      "this value so I take your commands.")
        await telegram.send_text(chat_id, HELP + owner_note)
        return

    # Everything below is sensitive → owner only.
    if not _is_owner(chat_id):
        await telegram.send_text(
            chat_id, "I only take orders from my principal. "
                     f"(Your chat id: `{chat_id}`)")
        return

    if cmd == "status":
        await _cmd_status(chat_id)
    elif cmd == "run":
        await _cmd_run(chat_id, arg)
    elif cmd == "positions":
        await _cmd_positions(chat_id)
    elif cmd == "mode":
        await _cmd_mode(chat_id, arg)
    else:
        await _cmd_chat(chat_id, text)


async def _cmd_status(chat_id: str) -> None:
    orch = get_orchestrator()
    acct = broker.get_account()
    mode = current_mode()
    parts = [f"*Status* · _{mode}_"]
    if acct:
        pnl = float(acct.get("equity", 0)) - float(acct.get("lastEquity", 0))
        parts.append(f"Equity {_money(acct.get('equity'))} "
                     f"({'+' if pnl >= 0 else ''}{_money(pnl)} today) · "
                     f"BP {_money(acct.get('buyingPower'))}")
    else:
        parts.append("_No live account (mock mode)._")
    d = orch.last_decision
    if d:
        parts.append(f"Last decision: *{d.posture}*, gate "
                     f"{'OPEN' if d.riskGateOpen else 'CLOSED'} — {d.headline}")
    else:
        parts.append("No swarm cycle run yet. Send /run.")
    await telegram.send_text(chat_id, "\n".join(parts))


async def _cmd_run(chat_id: str, arg: str) -> None:
    capital = 1000
    if arg.isdigit():
        capital = max(100, int(arg))
    await telegram.send_text(chat_id, f"Running the swarm at {_money(capital)}/trade…")
    decision = get_orchestrator().run_cycle(capital=capital)
    await brief(chat_id, decision.model_dump(), voice=True)


async def _cmd_positions(chat_id: str) -> None:
    positions = broker.list_positions() if broker.is_configured() else []
    if not positions:
        await telegram.send_text(chat_id, "Flat — no open positions." if broker.is_configured()
                                 else "_Mock mode — no live positions._")
        return
    lines = ["*Open positions*"]
    for p in positions[:15]:
        lines.append(f"• {p.get('ticker')} {p.get('strategyLabel','')} ×{p.get('contracts',1)} "
                     f"(entry {_money(p.get('entryPremium'))} → now {_money(p.get('currentPremium'))})")
    await telegram.send_text(chat_id, "\n".join(lines))


async def _cmd_mode(chat_id: str, arg: str) -> None:
    arg = arg.lower()
    if not arg:
        await telegram.send_text(
            chat_id, f"Mode is *{current_mode()}*.\n"
                     "`/mode paper` to go safe · `/mode live confirm` for REAL orders.")
        return
    if not broker.is_configured():
        await telegram.send_text(chat_id, "Broker isn't configured (mock mode) — nothing to switch.")
        return
    if arg.startswith("paper"):
        broker.set_paper_mode(True)
        await telegram.send_text(chat_id, "✅ Switched to *PAPER*. Orders are simulated.")
    elif arg.startswith("live"):
        if "confirm" not in arg:
            await telegram.send_text(
                chat_id, "⚠️ *LIVE = real money.* Re-send `/mode live confirm` to proceed.")
            return
        broker.set_paper_mode(False)
        await telegram.send_text(chat_id, "🔴 Switched to *LIVE*. Real orders are now armed.")
    else:
        await telegram.send_text(chat_id, "Use `/mode paper` or `/mode live confirm`.")


async def _cmd_chat(chat_id: str, question: str) -> None:
    reply = _gemini_reply(question)
    await telegram.send_text(chat_id, reply)
    await telegram.send_voice(chat_id, reply)


def _gemini_reply(question: str) -> str:
    key = os.getenv("GEMINI_API_KEY")
    if not key:
        return ("I'm running without a brain right now — set GEMINI_API_KEY and I'll "
                "answer properly. Meanwhile try /status, /run, /positions or /mode.")
    try:
        import google.generativeai as genai
        genai.configure(api_key=key)
        ctx = _live_context()
        model = genai.GenerativeModel("gemini-1.5-flash", system_instruction=JARVIS_PERSONA)
        resp = model.generate_content(f"{ctx}\n\nPrincipal: {question}")
        return (resp.text or "I couldn't find the words.").strip()
    except Exception as e:
        log.warning("[jarvis] gemini reply failed: %s", e)
        return "My uplink to Gemini just glitched — try again in a moment."


def _live_context() -> str:
    """A compact snapshot the model can ground answers in."""
    bits = [f"[Live context] mode={current_mode()}."]
    acct = broker.get_account()
    if acct:
        bits.append(f"equity={_money(acct.get('equity'))}, "
                    f"buying_power={_money(acct.get('buyingPower'))}.")
    d = get_orchestrator().last_decision
    if d:
        acts = ", ".join(f"{a.action} {a.ticker}" for a in d.actions[:5]) or "none"
        bits.append(f"last_posture={d.posture}, risk_gate="
                    f"{'open' if d.riskGateOpen else 'closed'}, actions=[{acts}].")
    return " ".join(bits)
