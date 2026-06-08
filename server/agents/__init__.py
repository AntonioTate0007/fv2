"""
Fortress agent swarm.

A small, verifiable multi-agent system that sits on top of the existing Alpaca
broker integration. Three specialist sub-agents each run their own analysis pass
and *report back* to a single main agent (the orchestrator), which fuses their
reports into one consolidated portfolio decision.

    ┌─────────────────────────────────────────────────────────────┐
    │                     MainOrchestrator                        │
    │   collects AgentReports → fuses → consolidated decision     │
    └───────▲───────────────────▲───────────────────▲─────────────┘
            │ report            │ report            │ report
    ┌───────┴──────┐   ┌────────┴───────┐   ┌───────┴────────┐
    │ AutoHedge    │   │ VibeTrading    │   │ Fincept        │
    │ (risk/hedge) │   │ (sentiment)    │   │ (market data)  │
    └──────────────┘   └────────────────┘   └────────────────┘

Inspired by:
  • Auto Hedge / ZkVanguard  — verifiable multi-agent risk orchestration
  • Vibe-Trading (HKUDS)     — LLM/sentiment-driven trade ideation
  • Fincept Terminal         — market-data terminal & analytics backbone
"""

from .base import AgentReport, AgentStatus, Signal, SignalDirection
from .orchestrator import MainOrchestrator, get_orchestrator

__all__ = [
    "AgentReport",
    "AgentStatus",
    "Signal",
    "SignalDirection",
    "MainOrchestrator",
    "get_orchestrator",
]
