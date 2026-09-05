"""Background loop: fires due reminders and the optional morning briefing."""

from __future__ import annotations

import asyncio
import datetime as dt
import logging
from typing import Awaitable, Callable, Optional

from .agent import Agent, Outgoing
from .config import Settings

log = logging.getLogger("jarvis.scheduler")

Deliver = Callable[[Optional[str], Outgoing], Awaitable[None]]


async def morning_brief(agent: Agent) -> Outgoing:
    parts = [dt.datetime.now().strftime("Good morning. It's %A, %d %B.")]
    for tool in ("battery", "fortress_status", "reminders"):
        if agent.registry.get(tool):
            r = await agent.registry.execute(tool, {})
            if r.text and not r.text.startswith(("Phone says no", "Fortress isn't linked")):
                parts.append(r.text)
    return Outgoing("\n".join(parts))


async def run(agent: Agent, s: Settings, deliver: Deliver, *, tick: float = 20.0) -> None:
    last_brief_day: Optional[dt.date] = None
    while True:
        try:
            for r in agent.memory.pop_due():
                out = await agent.deliver_reminder(r)
                await deliver(r.get("chat_id"), out)

            if s.morning_brief:
                now = dt.datetime.now()
                hh, _, mm = s.morning_brief.partition(":")
                if (now.hour, now.minute) >= (int(hh), int(mm or 0)) and last_brief_day != now.date():
                    last_brief_day = now.date()
                    await deliver(None, await morning_brief(agent))
        except Exception as e:
            log.warning("scheduler tick failed: %s", e)
        await asyncio.sleep(tick)
