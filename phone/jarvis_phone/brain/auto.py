from __future__ import annotations

import logging
from typing import Optional

from ..config import Settings
from .base import Brain, Context, Plan
from .llm import GeminiBrain, LocalLLMBrain
from .rules import RulesBrain

log = logging.getLogger("jarvis.brain")


class AutoBrain:
    """Try each brain in order; first one with an answer wins. Rules go first so
    the common commands never touch a model."""
    label = "auto"

    def __init__(self, chain: list[Brain]):
        self.chain = chain

    async def healthy(self) -> tuple[bool, str]:
        parts = []
        for b in self.chain:
            ok, msg = await b.healthy()
            parts.append(f"{b.label}: {'ok' if ok else 'down'} ({msg})")
        return bool(self.chain), " · ".join(parts)

    async def plan(self, text: str, ctx: Context) -> Optional[Plan]:
        for b in self.chain:
            try:
                plan = await b.plan(text, ctx)
            except Exception as e:  # a broken brain must not take the bot down
                log.warning("%s brain crashed: %s", b.label, e)
                plan = None
            if plan is not None:
                return plan
        return None


def make_brain(s: Settings) -> Brain:
    rules = RulesBrain()
    local = LocalLLMBrain(s.local_llm_url, s.local_llm_model, s.local_llm_timeout)
    gemini = GeminiBrain(s.gemini_api_key, s.gemini_model)
    mode = s.brain
    if mode == "rules":
        return rules
    if mode == "local":
        return AutoBrain([rules, local])
    if mode == "gemini":
        return AutoBrain([rules, gemini])
    chain: list[Brain] = [rules]
    if s.local_llm_url:
        chain.append(local)
    if s.gemini_api_key:
        chain.append(gemini)
    return AutoBrain(chain)
