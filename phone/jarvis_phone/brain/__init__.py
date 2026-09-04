"""Brains decide what to do with a message: answer directly, or call a tool.

- `RulesBrain`    — regex intents. Instant, offline, zero dependencies.
- `LocalLLMBrain` — any OpenAI-compatible server on the phone (llama-server,
                    cactus, ollama…). Prompt-based JSON tool calling so it works
                    with tiny models that have no native function-calling.
- `GeminiBrain`   — Google Gemini over REST (httpx only, no SDK).
- `AutoBrain`     — rules first, then local, then Gemini; whichever is available.
"""

from .base import Brain, Context, Plan
from .rules import RulesBrain
from .llm import LocalLLMBrain, GeminiBrain, parse_plan
from .auto import AutoBrain, make_brain

__all__ = ["Brain", "Context", "Plan", "RulesBrain", "LocalLLMBrain", "GeminiBrain",
           "AutoBrain", "make_brain", "parse_plan"]
