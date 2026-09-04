"""The agent loop: message in → brain → (maybe confirm) → tool → replies out.

Transport-agnostic: Telegram and the terminal REPL both call `Agent.handle()` and
get back a list of `Outgoing` items to deliver however they like."""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Optional

from . import overlay, termux
from .brain import Brain, Context, Plan, make_brain
from .config import Settings, settings as default_settings
from .fortress import Fortress
from .memory import Memory
from .tools import Registry, ToolResult, build_registry

log = logging.getLogger("jarvis.agent")

YES = re.compile(r"^\s*(y|yes|yeah|yep|confirm|do it|go ahead|ok|okay|sure)\b", re.I)
NO = re.compile(r"^\s*(n|no|nope|cancel|stop|abort|never ?mind)\b", re.I)
PENDING_TTL = 120.0


@dataclass
class Outgoing:
    text: str
    photo: Optional[str] = None
    speak: Optional[str] = None       # what to say aloud; None = same as text


@dataclass
class Pending:
    tool: str
    args: dict
    created: float = field(default_factory=time.time)

    @property
    def expired(self) -> bool:
        return time.time() - self.created > PENDING_TTL


class Agent:
    def __init__(self, s: Settings | None = None, *, memory: Memory | None = None,
                 registry: Registry | None = None, brain: Brain | None = None,
                 fortress: Fortress | None = None):
        self.settings = s or default_settings
        self.memory = memory or Memory(self.settings.data_dir)
        self.fortress = fortress or Fortress(self.settings.fortress_url,
                                             self.settings.fortress_token)
        self.registry = registry or build_registry(self.memory, self.fortress, self.settings)
        self.brain = brain or make_brain(self.settings)
        self.speak = self.memory.pref("speak", self.settings.speak)
        self._pending: dict[str, Pending] = {}   # per chat

    # ── public ────────────────────────────────────────────────────────────
    def context(self) -> Context:
        return Context(history=self.memory.history(),
                       tools_spec=self.registry.prompt_spec(),
                       tool_names=self.registry.names(),
                       name=self.settings.name)

    def set_speak(self, on: bool) -> None:
        self.speak = on
        self.memory.set_pref("speak", on)

    async def handle(self, text: str, chat_id: str = "local") -> list[Outgoing]:
        text = (text or "").strip()
        if not text:
            return []

        # confirmation round-trip for dangerous tools
        pending = self._pending.get(chat_id)
        if pending and pending.expired:
            self._pending.pop(chat_id, None)
            pending = None
        if pending:
            if YES.match(text):
                self._pending.pop(chat_id, None)
                return await self._run_tool(pending.tool, pending.args, chat_id)
            if NO.match(text):
                self._pending.pop(chat_id, None)
                return [Outgoing("Cancelled.")]
            self._pending.pop(chat_id, None)  # anything else: drop it, carry on

        self.memory.remember("user", text)
        plan = await self.brain.plan(text, self.context())
        if plan is None or plan.is_noop:
            out = [Outgoing(self._fallback_reply(text))]
        else:
            out = await self._execute(plan, chat_id)
        for o in out:
            self.memory.remember("assistant", o.text)
        return out

    async def deliver_reminder(self, r: dict) -> Outgoing:
        text = f"⏰ Reminder: {r['text']}"
        try:
            termux.notify("Reminder", r["text"], notif_id=f"jarvis-rem-{r['id']}")
        except Exception:
            pass
        return Outgoing(text, speak=f"Reminder: {r['text']}")

    # ── internals ─────────────────────────────────────────────────────────
    async def _execute(self, plan: Plan, chat_id: str) -> list[Outgoing]:
        out: list[Outgoing] = []
        if plan.tool is None:
            return [Outgoing(plan.reply or "")]
        tool = self.registry.get(plan.tool)
        if tool is None:
            return [Outgoing(f"I don't have a '{plan.tool}' tool.")]
        if plan.reply:
            out.append(Outgoing(plan.reply))
        if tool.confirm:
            self._pending[chat_id] = Pending(tool.name, plan.args)
            desc = ", ".join(f"{k}={v}" for k, v in plan.args.items() if not k.startswith("_"))
            out.append(Outgoing(f"About to run *{tool.name}*"
                                f"{f' ({desc})' if desc else ''}. Reply *yes* to confirm."))
            return out
        out.extend(await self._run_tool(tool.name, plan.args, chat_id))
        return out

    async def _run_tool(self, name: str, args: dict, chat_id: str) -> list[Outgoing]:
        args = dict(args)
        args["_chat_id"] = chat_id
        log.info("tool %s %s", name, {k: v for k, v in args.items() if not k.startswith("_")})
        result: ToolResult = await self.registry.execute(name, args)
        return [Outgoing(result.text, photo=result.photo, speak=result.speak)]

    def _fallback_reply(self, text: str) -> str:
        return ("I didn't catch that. I can check the battery, Wi-Fi or location, "
                "toggle the flashlight, set reminders and notes, take a photo, or "
                "report on Fortress. Type /help for the full list.")

    async def speak_aloud(self, o: Outgoing) -> None:
        if not self.speak:
            return
        say = o.text if o.speak is None else o.speak
        say = re.sub(r"[*_`]", "", say)
        say = re.sub(r"https?://\S+", "", say).strip()
        if not say:
            return
        try:
            with overlay.speaking_while(say):
                termux.speak(say[:400])
        except Exception as e:
            log.debug("tts unavailable: %s", e)
