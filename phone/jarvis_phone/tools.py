"""The tool registry: everything Jarvis can *do*.

Each tool is a plain function taking a dict of args and returning a human-readable
string (or a `ToolResult` when it also produces a file, e.g. a photo). Tools are
described once here and that description is what the brains see — the rules brain
maps intents onto tool names, the LLM brains get the same list rendered into
their prompt.
"""

from __future__ import annotations

import datetime as dt
import inspect
import re
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

from . import termux
from .config import Settings, settings as default_settings
from .fortress import Fortress
from .memory import Memory
from .termux import TermuxError


@dataclass
class ToolResult:
    text: str
    photo: Optional[str] = None      # path to an image to send along
    speak: Optional[str] = None      # override for what gets spoken aloud

    def __str__(self) -> str:
        return self.text


Handler = Callable[[dict], "str | ToolResult | Awaitable[str | ToolResult]"]


@dataclass
class Tool:
    name: str
    description: str
    handler: Handler
    params: dict[str, str] = field(default_factory=dict)  # name -> description
    confirm: bool = False           # ask the principal before running
    examples: list[str] = field(default_factory=list)

    def spec(self) -> str:
        p = ", ".join(f"{k}: {v}" for k, v in self.params.items()) or "no args"
        return f"- {self.name}({p}): {self.description}"


class Registry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def add(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def get(self, name: str) -> Optional[Tool]:
        return self._tools.get(name)

    def names(self) -> list[str]:
        return list(self._tools)

    def all(self) -> list[Tool]:
        return list(self._tools.values())

    def prompt_spec(self) -> str:
        return "\n".join(t.spec() for t in self._tools.values())

    async def execute(self, name: str, args: dict | None = None) -> ToolResult:
        tool = self.get(name)
        if not tool:
            return ToolResult(f"I don't have a tool called '{name}'.")
        try:
            out = tool.handler(args or {})
            if inspect.isawaitable(out):
                out = await out
        except TermuxError as e:
            return ToolResult(f"Phone says no: {e}")
        except Exception as e:  # never let a tool crash the bot
            return ToolResult(f"{name} failed: {e.__class__.__name__}: {e}")
        return out if isinstance(out, ToolResult) else ToolResult(str(out))


# ── helpers ────────────────────────────────────────────────────────────────────

def _int(args: dict, key: str, default: int) -> int:
    try:
        return int(float(str(args.get(key, default)).strip().rstrip("%")))
    except (TypeError, ValueError):
        return default


def _text(args: dict, *keys: str, default: str = "") -> str:
    for k in keys:
        v = args.get(k)
        if v is not None and str(v).strip():
            return str(v).strip()
    return default


def _truthy(v: Any) -> bool:
    return str(v).strip().lower() in ("1", "true", "yes", "on", "enable", "enabled")


def parse_duration(s: str) -> Optional[int]:
    """'in 10 minutes' / '2h' / '1 hour 30 min' / '45s' → seconds."""
    s = s.lower()
    total = 0
    found = False
    for num, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?|s(?:ec(?:ond)?s?)?|d(?:ays?)?)\b", s):
        n = float(num)
        found = True
        if unit.startswith("h"):
            total += n * 3600
        elif unit.startswith("m"):
            total += n * 60
        elif unit.startswith("d"):
            total += n * 86400
        else:
            total += n
    return int(total) if found and total > 0 else None


def parse_clock(s: str, now: Optional[dt.datetime] = None) -> Optional[dt.datetime]:
    """'at 7', '7:30pm', 'at 19:05' → next occurrence of that wall-clock time."""
    m = re.search(r"\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b", s.lower())
    if not m:
        return None
    hour, minute, ampm = int(m.group(1)), int(m.group(2) or 0), m.group(3)
    if hour > 23 or minute > 59:
        return None
    if ampm == "pm" and hour < 12:
        hour += 12
    if ampm == "am" and hour == 12:
        hour = 0
    now = now or dt.datetime.now()
    when = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if when <= now:
        when += dt.timedelta(days=1)
    return when


def fmt_when(ts: float) -> str:
    d = dt.datetime.fromtimestamp(ts)
    today = dt.date.today()
    if d.date() == today:
        return d.strftime("%H:%M")
    if d.date() == today + dt.timedelta(days=1):
        return "tomorrow " + d.strftime("%H:%M")
    return d.strftime("%a %d %b %H:%M")


# ── the tools ──────────────────────────────────────────────────────────────────

def build_registry(memory: Memory, fortress: Optional[Fortress] = None,
                   settings: Settings | None = None) -> Registry:
    reg = Registry()
    settings = settings or default_settings
    fortress = fortress or Fortress(settings.fortress_url, settings.fortress_token)

    # ── phone sensors ─────────────────────────────────────────────────────
    def t_battery(args: dict) -> str:
        b = termux.battery()
        pct = b.get("percentage", "?")
        status = str(b.get("status", "")).lower().replace("_", " ")
        temp = b.get("temperature")
        plugged = str(b.get("plugged", "")).lower()
        parts = [f"Battery is at {pct}%"]
        if status:
            parts.append(status if "charging" in status else f"status {status}")
        if plugged and plugged not in ("unplugged", "false"):
            parts.append(f"plugged in via {plugged.replace('plugged_', '')}")
        if temp is not None:
            parts.append(f"{float(temp):.0f}°C")
        return ", ".join(parts) + "."

    reg.add(Tool("battery", "Battery percentage, charging state and temperature.",
                 t_battery, examples=["battery", "how much charge do I have"]))

    def t_location(args: dict) -> str:
        loc = termux.location()
        lat, lon = loc.get("latitude"), loc.get("longitude")
        if lat is None:
            return "No fix yet — try again in a moment, ideally near a window."
        acc = loc.get("accuracy")
        url = f"https://maps.google.com/?q={lat:.5f},{lon:.5f}"
        return (f"You're at {lat:.5f}, {lon:.5f}"
                f"{f' (±{acc:.0f} m)' if acc else ''}. {url}")

    reg.add(Tool("location", "Current GPS/network location with a map link.",
                 t_location, examples=["where am I", "location"]))

    def t_wifi(args: dict) -> str:
        w = termux.wifi()
        ssid = (w.get("ssid") or "").strip('"')
        if not ssid or ssid == "<unknown ssid>":
            return "Not connected to Wi-Fi."
        ip = w.get("ip")
        rssi = w.get("rssi")
        speed = w.get("link_speed_mbps")
        parts = [f"On Wi-Fi '{ssid}'"]
        if ip:
            parts.append(f"IP {ip}")
        if rssi is not None:
            parts.append(f"signal {rssi} dBm")
        if speed:
            parts.append(f"{speed} Mbps")
        return ", ".join(parts) + "."

    reg.add(Tool("wifi", "Wi-Fi network name, IP, signal strength.",
                 t_wifi, examples=["what wifi am I on", "wifi"]))

    def t_device(args: dict) -> str:
        parts = []
        try:
            up = termux.shell(["uptime", "-p"])
            if up:
                parts.append(up.replace("up ", "Up "))
        except Exception:
            pass
        try:
            df = termux.shell(["df", "-h", "/data/data/com.termux/files/home"]).splitlines()
            if len(df) >= 2:
                cols = df[1].split()
                if len(cols) >= 5:
                    parts.append(f"storage {cols[3]} free of {cols[1]} ({cols[4]} used)")
        except Exception:
            pass
        try:
            free = termux.shell(["free", "-m"]).splitlines()
            for line in free:
                if line.lower().startswith("mem:"):
                    c = line.split()
                    parts.append(f"RAM {c[2]} MB used of {c[1]} MB")
        except Exception:
            pass
        return (". ".join(parts) + ".") if parts else "Couldn't read device stats here."

    reg.add(Tool("device", "Uptime, free storage and RAM of the phone.",
                 t_device, examples=["device status", "how much storage is left"]))

    def t_time(args: dict) -> str:
        return dt.datetime.now().strftime("It's %H:%M on %A, %d %B %Y.")

    reg.add(Tool("time", "Current date and time.", t_time,
                 examples=["what time is it", "date"]))

    # ── phone actions ─────────────────────────────────────────────────────
    def t_torch(args: dict) -> str:
        on = _truthy(args.get("on", "on"))
        termux.torch(on)
        return "Flashlight on." if on else "Flashlight off."

    reg.add(Tool("torch", "Turn the flashlight on or off.", t_torch,
                 params={"on": "true|false"},
                 examples=["turn on the flashlight", "torch off"]))

    def t_vibrate(args: dict) -> str:
        ms = _int(args, "ms", 500)
        termux.vibrate(ms)
        return f"Buzzed for {ms} ms."

    reg.add(Tool("vibrate", "Vibrate the phone.", t_vibrate,
                 params={"ms": "duration in milliseconds, default 500"},
                 examples=["vibrate", "buzz my phone"]))

    def t_notify(args: dict) -> str:
        title = _text(args, "title", default=settings.name)
        body = _text(args, "text", "content", "message")
        if not body:
            return "What should the notification say?"
        termux.notify(title, body)
        return f"Notification posted: {body}"

    reg.add(Tool("notify", "Post an Android notification.", t_notify,
                 params={"text": "notification body", "title": "optional title"},
                 examples=["notify me: drink water"]))

    def t_speak(args: dict) -> ToolResult:
        text = _text(args, "text", "message")
        if not text:
            return ToolResult("What should I say?")
        termux.speak(text)
        return ToolResult(f"Said: {text}", speak="")   # already spoken, don't repeat

    reg.add(Tool("speak", "Say something out loud through the phone speaker.", t_speak,
                 params={"text": "what to say"},
                 examples=["say hello world", "speak: dinner is ready"]))

    def t_clipboard_get(args: dict) -> str:
        c = termux.clipboard_get().strip()
        return f"Clipboard: {c[:1500]}" if c else "Clipboard is empty."

    reg.add(Tool("clipboard_get", "Read the clipboard.", t_clipboard_get,
                 examples=["what's on my clipboard"]))

    def t_clipboard_set(args: dict) -> str:
        text = _text(args, "text")
        if not text:
            return "What should I copy?"
        termux.clipboard_set(text)
        return f"Copied: {text[:200]}"

    reg.add(Tool("clipboard_set", "Put text on the clipboard.", t_clipboard_set,
                 params={"text": "text to copy"}, examples=["copy this: hello"]))

    def t_brightness(args: dict) -> str:
        pct = max(0, min(_int(args, "percent", 50), 100))
        termux.brightness(round(pct * 255 / 100))
        return f"Brightness set to {pct}%."

    reg.add(Tool("brightness", "Set screen brightness.", t_brightness,
                 params={"percent": "0-100"}, examples=["brightness 30%", "dim the screen"]))

    def t_volume(args: dict) -> str:
        stream = _text(args, "stream", default="music").lower()
        if stream not in ("music", "ring", "notification", "alarm", "system", "call"):
            stream = "music"
        pct = max(0, min(_int(args, "percent", 50), 100))
        max_vol = 15
        for v in termux.volumes():
            if v.get("stream") == stream and v.get("max_volume"):
                max_vol = int(v["max_volume"])
        termux.volume(stream, round(pct * max_vol / 100))
        return f"{stream.capitalize()} volume set to {pct}%."

    reg.add(Tool("volume", "Set a volume level.", t_volume,
                 params={"percent": "0-100", "stream": "music|ring|notification|alarm"},
                 examples=["volume 20%", "mute the ringer", "set alarm volume to 80"]))

    def t_photo(args: dict) -> ToolResult:
        cam = "1" if _text(args, "camera", default="back").lower() in ("1", "front", "selfie") else "0"
        path = str(Path(tempfile.gettempdir()) / f"jarvis-{int(time.time())}.jpg")
        termux.photo(path, cam)
        return ToolResult("Here's the photo.", photo=path, speak="Photo taken.")

    reg.add(Tool("photo", "Take a photo with the back or front camera and send it.",
                 t_photo, params={"camera": "back|front"},
                 examples=["take a photo", "selfie"]))

    def t_open(args: dict) -> str:
        url = _text(args, "url")
        if not url:
            return "Which URL?"
        if not re.match(r"^https?://", url):
            url = "https://" + url
        termux.open_url(url)
        return f"Opened {url}"

    reg.add(Tool("open_url", "Open a web page on the phone.", t_open,
                 params={"url": "address"}, examples=["open youtube.com"]))

    def t_sms(args: dict) -> str:
        if not settings.allow_sms:
            return "SMS is disabled. Set JARVIS_ALLOW_SMS=on to enable it."
        number = re.sub(r"[^\d+]", "", _text(args, "number", "to"))
        text = _text(args, "text", "message")
        if not number or not text:
            return "I need a number and a message."
        termux.sms_send(number, text)
        return f"Sent to {number}: {text}"

    reg.add(Tool("sms_send", "Send a text message (asks for confirmation).", t_sms,
                 params={"number": "phone number", "text": "message"}, confirm=True,
                 examples=["text +15551234567: running late"]))

    # ── memory: reminders + notes ─────────────────────────────────────────
    def t_remind(args: dict) -> str:
        text = _text(args, "text", "what")
        when_s = _text(args, "when", "in")
        secs = parse_duration(when_s) if when_s else None
        when_ts: Optional[float] = None
        if secs:
            when_ts = time.time() + secs
        elif when_s:
            clock = parse_clock(when_s)
            if clock:
                when_ts = clock.timestamp()
        if not when_ts:
            return "When? Say something like 'in 20 minutes' or 'at 7:30pm'."
        if not text:
            return "Remind you of what?"
        r = memory.add_reminder(when_ts, text, args.get("_chat_id"))
        return f"Got it — I'll remind you {fmt_when(r['when'])}: {text}"

    reg.add(Tool("remind", "Set a reminder. Fires as a Telegram message, a phone "
                 "notification and (if speaking is on) out loud.", t_remind,
                 params={"when": "'in 10 minutes' or 'at 7:30pm'", "text": "what to remind"},
                 examples=["remind me in 20 minutes to call mom",
                           "remind me at 6pm to take the trash out"]))

    def t_reminders(args: dict) -> str:
        rs = memory.reminders()
        if not rs:
            return "No reminders set."
        return "Reminders:\n" + "\n".join(f"• {fmt_when(r['when'])} — {r['text']}" for r in rs)

    reg.add(Tool("reminders", "List pending reminders.", t_reminders,
                 examples=["what reminders do I have"]))

    def t_cancel_reminders(args: dict) -> str:
        n = memory.cancel_reminders()
        return f"Cancelled {n} reminder{'s' if n != 1 else ''}."

    reg.add(Tool("cancel_reminders", "Cancel all reminders.", t_cancel_reminders,
                 examples=["cancel my reminders"]))

    def t_note(args: dict) -> str:
        text = _text(args, "text")
        if not text:
            return "Note what?"
        n = memory.add_note(text)
        return f"Noted (#{n}): {text}"

    reg.add(Tool("note", "Save a note.", t_note, params={"text": "the note"},
                 examples=["note: buy milk", "remember that the wifi password is hunter2"]))

    def t_notes(args: dict) -> str:
        ns = memory.notes()
        if not ns:
            return "No notes yet."
        return "Notes:\n" + "\n".join(f"{i + 1}. {n['text']}" for i, n in enumerate(ns[-20:]))

    reg.add(Tool("notes", "List saved notes.", t_notes, examples=["show my notes"]))

    def t_clear_notes(args: dict) -> str:
        return f"Deleted {memory.clear_notes()} notes."

    reg.add(Tool("clear_notes", "Delete all notes (asks for confirmation).",
                 t_clear_notes, confirm=True, examples=["clear my notes"]))

    # ── Fortress (trading backend) ────────────────────────────────────────
    reg.add(Tool("fortress_status", "Trading account snapshot from Fortress: mode, "
                 "equity, today's P&L, last swarm decision.",
                 lambda a: fortress.status_text(),
                 examples=["how am I doing", "account status", "portfolio"]))
    reg.add(Tool("fortress_positions", "Open option positions from Fortress.",
                 lambda a: fortress.positions_text(),
                 examples=["positions", "what trades are open"]))

    async def t_run(args: dict) -> str:
        return await fortress.run_text(_int(args, "capital", 1000))

    reg.add(Tool("fortress_run", "Run one Fortress agent-swarm cycle and report "
                 "the decision. Paper/live follows the server's mode.", t_run,
                 params={"capital": "dollars per trade, default 1000"},
                 examples=["run the swarm", "run a cycle at 2500"]))

    return reg
