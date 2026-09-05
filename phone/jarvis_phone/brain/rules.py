"""Regex intent router. Handles the everyday commands without any model, which
matters on a phone: no latency, no battery, no network, no API bill."""

from __future__ import annotations

import re
from typing import Callable, Optional

from .base import Context, Plan

Matcher = Callable[[re.Match, str], Plan]

_NUM = r"(\d{1,3})\s*(?:%|percent)?"


def _pct(m: re.Match, key: str = "percent") -> dict:
    return {key: int(m.group(1))}


def _on_off(s: str) -> str:
    return "false" if re.search(r"\b(off|out|stop|kill|disable)\b", s) else "true"


class RulesBrain:
    label = "rules"

    def __init__(self) -> None:
        self.rules: list[tuple[re.Pattern, Matcher]] = []
        self._install_defaults()

    def add(self, pattern: str, make: Matcher) -> None:
        self.rules.append((re.compile(pattern, re.I), make))

    async def healthy(self) -> tuple[bool, str]:
        return True, f"{len(self.rules)} intents"

    async def plan(self, text: str, ctx: Context) -> Optional[Plan]:
        s = text.strip()
        s_norm = re.sub(r"\s+", " ", s)
        for pat, make in self.rules:
            m = pat.search(s_norm)
            if m:
                plan = make(m, s_norm)
                plan.source = self.label
                return plan
        return None

    # ── intents ───────────────────────────────────────────────────────────
    def _install_defaults(self) -> None:
        A = self.add
        T = lambda name, **args: (lambda m, s: Plan(tool=name, args=dict(args)))  # noqa: E731

        # phone sensors
        A(r"\b(battery|charge|charging|power level)\b", T("battery"))
        A(r"\b(where am i|location|gps|my position|coordinates)\b", T("location"))
        A(r"\b(wi-?fi|network name|ssid|ip address)\b", T("wifi"))
        A(r"\b(device|phone|system) (status|stats|info|health)\b|\b(uptime|storage|disk|ram|memory) (left|free|usage|status)?\b", T("device"))
        A(r"\b(what time|what's the time|whats the time|the time|current time|what day|what date|today's date|the date)\b", T("time"))

        # phone actions
        A(r"\b(flash ?light|torch|lamp)\b",
          lambda m, s: Plan(tool="torch", args={"on": _on_off(s)}))
        A(r"\b(vibrate|buzz|shake)\b(?:.*?(\d{2,4})\s*ms)?",
          lambda m, s: Plan(tool="vibrate", args={"ms": int(m.group(2))} if m.group(2) else {}))
        A(r"^(?:notify|notification|ping me)\s*(?:me)?\s*[:\-]?\s*(.+)$",
          lambda m, s: Plan(tool="notify", args={"text": m.group(1)}))
        A(r"^(?:say|speak|announce|tell everyone)\s*[:\-]?\s*(.+)$",
          lambda m, s: Plan(tool="speak", args={"text": m.group(1)}))
        A(r"\b(clipboard|what did i copy|what's copied|paste)\b", T("clipboard_get"))
        A(r"^(?:copy|clip)\s*(?:this|that)?\s*[:\-]?\s*(.+)$",
          lambda m, s: Plan(tool="clipboard_set", args={"text": m.group(1)}))
        A(r"\bbrightness\b.*?" + _NUM, lambda m, s: Plan(tool="brightness", args=_pct(m)))
        A(r"\b(dim|darken)\b.*\bscreen\b|\bscreen\b.*\b(dim|darker)\b",
          T("brightness", percent=15))
        A(r"\b(brighten|max brightness|full brightness)\b", T("brightness", percent=100))
        A(r"\b(mute|silence|silent)\b.*\b(ring|ringer|phone)\b|\b(ring|ringer)\b.*\b(mute|silence|silent)\b",
          T("volume", percent=0, stream="ring"))
        A(r"\b(mute|silence)\b", T("volume", percent=0, stream="music"))
        A(r"\b(alarm|ring(?:er)?|notification|media|music)?\s*volume\b.*?" + _NUM,
          lambda m, s: Plan(tool="volume", args={"percent": int(m.group(2)),
                                                 "stream": (m.group(1) or "music").lower()
                                                 .replace("ringer", "ring").replace("media", "music")}))
        A(r"\b(selfie|front camera|front cam)\b", T("photo", camera="front"))
        A(r"\b(take a (?:photo|picture|pic)|photo|snap(?:shot)?|camera)\b", T("photo", camera="back"))
        A(r"^open\s+((?:https?://)?[\w.-]+\.[a-z]{2,}\S*)$",
          lambda m, s: Plan(tool="open_url", args={"url": m.group(1)}))
        A(r"^(?:text|sms|message)\s+(\+?[\d\s\-()]{7,})\s*[:\-]\s*(.+)$",
          lambda m, s: Plan(tool="sms_send", args={"number": m.group(1), "text": m.group(2)}))

        # reminders + notes
        A(r"^remind (?:me )?(?:to )?(.*?)\s*\b(in\s+\d+(?:\.\d+)?\s*(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?|s(?:ec(?:ond)?s?)?|d(?:ays?)?)(?:\s*(?:and\s+)?\d+\s*(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?|s(?:ec(?:ond)?s?)?))?|at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\s*(?:to\s+)?(.*)$",
          lambda m, s: Plan(tool="remind", args={"when": m.group(2),
                                                 "text": (m.group(1) + " " + m.group(3)).strip()}))
        A(r"\b(cancel|clear|delete|remove)\b.*\breminders?\b", T("cancel_reminders"))
        A(r"\b(list|show|what|my|any)\b.*\breminders?\b|^reminders?$", T("reminders"))
        A(r"^(?:note|remember|jot down|write down)\s*(?:this|that)?\s*[:\-]?\s*(.+)$",
          lambda m, s: Plan(tool="note", args={"text": m.group(1)}))
        A(r"\b(clear|delete|wipe)\b.*\bnotes\b", T("clear_notes"))
        A(r"\b(show|list|read|my)\b.*\bnotes\b|^notes$", T("notes"))

        # Fortress
        A(r"\b(run|kick off|start)\b.*\b(swarm|cycle|agents)\b(?:.*?\$?(\d{3,6}))?",
          lambda m, s: Plan(tool="fortress_run", args={"capital": int(m.group(3))} if m.group(3) else {}))
        A(r"\b(positions?|open trades|what trades|spreads)\b", T("fortress_positions"))
        A(r"\b(how am i doing|account|portfolio|equity|p&l|pnl|status|fortress)\b", T("fortress_status"))

        # small talk that shouldn't burn a model call
        A(r"^(hi|hello|hey|yo|good (morning|evening|afternoon))\b",
          lambda m, s: Plan(reply="At your service. Ask me for the battery, flashlight, "
                                  "a reminder, or say /help."))
        A(r"^(thanks|thank you|cheers|ty)\b", lambda m, s: Plan(reply="Anytime."))
        A(r"^(who are you|what are you)\b",
          lambda m, s: Plan(reply="I'm your phone's local assistant. I run inside Termux "
                                  "and control the phone through Termux:API."))
