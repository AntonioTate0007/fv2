# Fortress Agent Swarm

A verifiable multi-agent system bolted onto the existing Alpaca-backed Fortress
backend. Three specialist **sub-agents** each analyze the same market snapshot and
**report back** to a single **main agent** (the orchestrator), which fuses their
reports into one consolidated trading decision.

```
                    ┌──────────────────────────────────┐
                    │         MainOrchestrator          │
   ConsolidatedDecision ◄── fuses reports ──► risk gate │
                    └───▲───────────▲───────────▲────────┘
        AgentReport ────┘           │           └──── AgentReport
                  ┌─────────────────┴──┐  ┌───────────┴──────────┐
       ┌──────────┴───────┐  ┌─────────┴──────┐  ┌───────────────┴──┐
       │   Auto Hedge      │  │  Vibe Trading  │  │ Fincept Terminal │
       │  risk / hedging   │  │   sentiment    │  │ data & analytics │
       └───────────────────┘  └────────────────┘  └──────────────────┘
```

## The three sub-agents

| Agent | Module | Inspired by | Role |
|-------|--------|-------------|------|
| **Auto Hedge** | `auto_hedge.py` | [ZkVanguard](https://github.com/ZkVanguard/ZkVanguard) | Watches open positions, measures capital-at-risk vs. equity, flags strikes under pressure and profit-take targets, emits `HEDGE`/`CLOSE` actions. **Acts as the risk gate.** |
| **Vibe Trading** | `vibe_trading.py` | [HKUDS/Vibe-Trading](https://github.com/HKUDS/Vibe-Trading) + [india-trade-cli](https://github.com/hopit-ai/india-trade-cli) | Generates directional ideas. Uses Gemini for a structured per-ticker "vibe" read when `GEMINI_API_KEY` is set; falls back to a transparent momentum heuristic offline. |
| **Fincept Terminal** | `fincept.py` | [FinceptTerminal](https://github.com/Fincept-Corporation/FinceptTerminal) | Quant backbone: SMA trend, RSI, rate-of-change, market-breadth regime (`RISK_ON`/`RISK_OFF`/`MIXED`). Confirms or vetoes Vibe's ideas. |

## How the main agent fuses reports

1. **Auto Hedge is the gate.** If capital-at-risk exceeds the 40 % equity ceiling,
   the risk gate **closes** — no new positions open that cycle; only hedge/close
   actions are actionable.
2. **Vibe proposes, Fincept confirms.** A bullish idea needs a tape that isn't
   `RISK_OFF`; a bearish idea needs a tape that isn't `RISK_ON`. When Fincept
   independently agrees on a name, conviction is boosted and both agents are
   credited as sources.
3. Confirmed ideas are ranked by conviction (risk-reducing actions first) and the
   top few become the orchestrator's **recommended actions**, sized within budget.

Every report is hashed (`contentHash`, SHA-256 over its meaningful fields) and the
consolidated decision records all three hashes (`reportHashes`) — so the chain from
sub-agent output to final decision is tamper-evident end-to-end.

## API

All routes are under the existing bearer-auth (`FORTRESS_API_TOKEN`).

| Route | Purpose |
|-------|---------|
| `POST /v1/agents/run` `{capital}` | Run one full cycle → `ConsolidatedDecision` |
| `GET /v1/agents/status` | Account snapshot + agent states + last decision |
| `GET /v1/agents/{name}/report` | One sub-agent's latest report (`auto_hedge` / `vibe_trading` / `fincept`) |
| `GET /dashboard` | Web dashboard (polls the routes above) |

## Dashboard

Open `http://<host>/dashboard`. Paste your `FORTRESS_API_TOKEN` (leave blank in
local mock mode), set capital per trade, and hit **Run Cycle** — or toggle **Auto**
for a 30-second refresh loop. It shows the account, the orchestrator's posture /
risk gate / recommended actions, and a live card per sub-agent with its signals and
content hash.

## Modes & configuration

No new env vars. The swarm reuses the backend's existing config and degrades
gracefully:

- **MOCK** — no `ALPACA_*` set: synthetic account/positions/quotes, deterministic
  analytics. Agents report `DEGRADED`. Good for development.
- **PAPER / LIVE** — `ALPACA_API_KEY` / `ALPACA_API_SECRET` set (paper unless
  `ALPACA_PAPER=false`): live account, positions, quotes and daily bars.
- **Gemini** — `GEMINI_API_KEY` set: Vibe Trading uses the LLM; otherwise it uses
  the offline momentum heuristic.

The swarm is read-and-recommend only. It surfaces actions on the dashboard; placing
them still goes through the existing audited `/v1/radar/deploy` and
`/v1/armory/close` routes (biometric-gated in the Android client).
