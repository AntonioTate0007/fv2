"""
Shared state store for the Autopilot backend.

The Android app and the autopilot engine (the Claude agent driving Robinhood)
need to share a little state:

    • settings        — guardrails + kill switch the app edits, the engine obeys
    • follows         — which model portfolios the user follows + per-portfolio
                        capital allocation
    • account         — a snapshot of the live Robinhood account, written by the
                        engine after each cycle, read by the app's Home screen
    • activity        — the trade log, written by the engine, read by the app's
                        Activity feed

This module is the single source of truth for that state. By default it persists
to a JSON file on disk (durable across restarts, zero external dependencies). If
SUPABASE_URL + SUPABASE_SERVICE_KEY are set it could be swapped for Supabase, but
the file backend keeps the MVP self-contained and verifiable.

Concurrency: a process-wide lock serialises read-modify-write so the API and any
background writer don't interleave. Single-process deployments only (Render free
tier is single-process) — add Postgres/Supabase before scaling out.
"""

from __future__ import annotations

import json
import os
import threading
import time
from typing import Any, Optional

_LOCK = threading.RLock()

# Where the JSON lives. Override with AUTOPILOT_STATE_FILE (e.g. a mounted disk).
_STATE_FILE = os.getenv("AUTOPILOT_STATE_FILE",
                        os.path.join(os.path.dirname(os.path.abspath(__file__)), "autopilot_state.json"))

# The single Robinhood account the engine is allowed to trade. Cash account,
# equities/ETFs only — the only agentic_allowed account on this login.
AGENTIC_ACCOUNT = os.getenv("RH_AGENTIC_ACCOUNT", "541689584")

# Default guardrails. The app's Settings screen edits these; the engine reads
# them at the top of every cycle and refuses to act outside them.
DEFAULT_SETTINGS: dict[str, Any] = {
    "killSwitch": False,         # True = autopilot paused, engine places no orders
    "allocatedCapital": 500.0,   # max $ the autopilot may deploy across all follows
    "maxPositionPct": 0.25,      # no single holding above this fraction of allocation
    "driftThresholdPct": 0.03,   # don't rebalance a name unless it drifts this far
    "rebalanceCadence": "daily", # informational: how often the engine runs a cycle
    "account": AGENTIC_ACCOUNT,
}

_DEFAULT_STATE: dict[str, Any] = {
    "settings": dict(DEFAULT_SETTINGS),
    "follows": {},        # portfolioId -> {"allocationPct": float, "followedAt": ts}
    "account": None,      # latest snapshot written by the engine
    "activity": [],       # newest-last list of trade-log entries
    "targets": {},        # portfolioId -> {"generatedAt": ts, "holdings": [...], "rationale": str}
}


def _read() -> dict[str, Any]:
    try:
        with open(_STATE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return json.loads(json.dumps(_DEFAULT_STATE))  # deep copy
    # Backfill any keys added in newer versions.
    for k, v in _DEFAULT_STATE.items():
        data.setdefault(k, json.loads(json.dumps(v)))
    for k, v in DEFAULT_SETTINGS.items():
        data["settings"].setdefault(k, v)
    return data


def _write(data: dict[str, Any]) -> None:
    tmp = _STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
    os.replace(tmp, _STATE_FILE)  # atomic on POSIX


# ── Settings ────────────────────────────────────────────────────────────────────

def get_settings() -> dict[str, Any]:
    with _LOCK:
        return _read()["settings"]


def update_settings(patch: dict[str, Any]) -> dict[str, Any]:
    """Merge a partial update into settings. Unknown keys are ignored so a stale
    client can't inject arbitrary fields."""
    with _LOCK:
        data = _read()
        for k in DEFAULT_SETTINGS:
            if k in patch and patch[k] is not None:
                data["settings"][k] = patch[k]
        _write(data)
        return data["settings"]


# ── Follows ─────────────────────────────────────────────────────────────────────

def get_follows() -> dict[str, Any]:
    with _LOCK:
        return _read()["follows"]


def set_follow(portfolio_id: str, following: bool, allocation_pct: Optional[float] = None) -> dict[str, Any]:
    with _LOCK:
        data = _read()
        if following:
            entry = data["follows"].get(portfolio_id, {"followedAt": time.time()})
            if allocation_pct is not None:
                entry["allocationPct"] = max(0.0, min(1.0, allocation_pct))
            entry.setdefault("allocationPct", 1.0)
            data["follows"][portfolio_id] = entry
        else:
            data["follows"].pop(portfolio_id, None)
        _write(data)
        return data["follows"]


# ── Account snapshot (written by the engine) ─────────────────────────────────────

def get_account() -> Optional[dict[str, Any]]:
    with _LOCK:
        return _read()["account"]


def save_account(snapshot: dict[str, Any]) -> None:
    with _LOCK:
        data = _read()
        snapshot = dict(snapshot)
        snapshot.setdefault("updatedAt", time.time())
        data["account"] = snapshot
        _write(data)


# ── Activity log (written by the engine) ─────────────────────────────────────────

def list_activity(limit: int = 50) -> list[dict[str, Any]]:
    with _LOCK:
        items = _read()["activity"]
        return items[-limit:][::-1]  # newest first


def append_activity(entry: dict[str, Any]) -> None:
    with _LOCK:
        data = _read()
        entry = dict(entry)
        entry.setdefault("at", time.time())
        entry.setdefault("id", f"act-{int(entry['at']*1000)}")
        data["activity"].append(entry)
        data["activity"] = data["activity"][-500:]  # cap history
        _write(data)


# ── Cached AI targets (written by refresh / engine) ───────────────────────────────

def get_targets(portfolio_id: str) -> Optional[dict[str, Any]]:
    with _LOCK:
        return _read()["targets"].get(portfolio_id)


def save_targets(portfolio_id: str, holdings: list[dict[str, Any]], rationale: str) -> dict[str, Any]:
    with _LOCK:
        data = _read()
        rec = {"generatedAt": time.time(), "holdings": holdings, "rationale": rationale}
        data["targets"][portfolio_id] = rec
        _write(data)
        return rec
