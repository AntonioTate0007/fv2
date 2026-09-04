"""Tap-to-talk: Android's speech recogniser in, phone speaker out.

Launched from a home-screen shortcut (Termux:Widget) or a notification button,
so it runs as its own short-lived process next to the bot daemon. Memory is shared
through the JSON file, so a reminder set by voice fires from the daemon."""

from __future__ import annotations

import logging
import re

from . import termux
from .agent import Agent, Outgoing

log = logging.getLogger("jarvis.voice")

BYE = re.compile(r"\b(bye|goodbye|that's all|thats all|stop listening|dismiss|never ?mind)\b", re.I)
CHAT_ID = "voice"


def _say(text: str) -> None:
    clean = re.sub(r"[*_`]", "", text)
    clean = re.sub(r"https?://\S+", "", clean).strip()
    if clean:
        try:
            termux.speak(clean[:400])
        except termux.TermuxError as e:
            log.warning("tts failed: %s", e)


def _show(text: str) -> None:
    try:
        termux.toast(text[:200])
    except termux.TermuxError:
        pass


async def _respond(agent: Agent, outs: list[Outgoing]) -> None:
    for o in outs:
        _show(o.text)
        say = o.text if o.speak is None else o.speak
        _say(say)
        if o.photo:
            try:
                termux.notify("Jarvis", o.text, notif_id="jarvis-photo")
            except termux.TermuxError:
                pass


async def listen(agent: Agent, *, conversation: bool = False, typed: bool = False,
                 max_turns: int = 10) -> int:
    """One voice (or typed) exchange; with `conversation` keep going until the user
    says bye or nothing is heard. Returns the number of turns handled."""
    turns = 0
    title = f"{agent.settings.name} is listening…"
    while turns < max_turns:
        try:
            heard = (termux.dialog_text(f"Ask {agent.settings.name}") if typed
                     else termux.dialog_speech(title))
        except termux.TermuxError as e:
            _say("I can't hear you — the Termux API app isn't responding.")
            log.warning("%s", e)
            return turns
        if not heard:
            if turns == 0:
                _say("I didn't catch that.")
            return turns
        log.info("heard: %s", heard)
        turns += 1
        if BYE.search(heard) and agent._pending.get(CHAT_ID) is None:
            _say("Goodbye.")
            return turns
        outs = await agent.handle(heard, CHAT_ID)
        await _respond(agent, outs)
        # keep the mic open while a confirmation is pending, or in conversation mode
        if not conversation and CHAT_ID not in agent._pending:
            return turns
        title = "Anything else?"
    return turns
