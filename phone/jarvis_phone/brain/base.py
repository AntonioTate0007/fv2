from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Protocol


@dataclass
class Plan:
    """What the brain wants to happen for one incoming message."""
    tool: Optional[str] = None            # tool name, or None for a plain reply
    args: dict = field(default_factory=dict)
    reply: Optional[str] = None           # text to send (before/instead of a tool)
    source: str = ""                      # which brain produced it (for /brain)

    @property
    def is_noop(self) -> bool:
        return self.tool is None and not self.reply


@dataclass
class Context:
    history: list[dict]                   # [{role, text}] most recent last
    tools_spec: str                       # rendered tool list for prompts
    tool_names: list[str]
    name: str = "Jarvis"


class Brain(Protocol):
    label: str

    async def plan(self, text: str, ctx: Context) -> Optional[Plan]:
        """Return a Plan, or None when this brain can't/doesn't want to handle it."""
        ...

    async def healthy(self) -> tuple[bool, str]:
        ...
