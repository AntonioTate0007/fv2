"""Entry points:

    python -m jarvis_phone            # Telegram bot + reminders (the daemon)
    python -m jarvis_phone chat       # talk to Jarvis in the terminal
    python -m jarvis_phone doctor     # check Termux:API, model, Telegram, Fortress
    python -m jarvis_phone once "battery"   # run one message and exit
    python -m jarvis_phone listen     # tap-to-talk: speech recogniser in, speaker out
    python -m jarvis_phone listen --conversation   # keep listening until "bye"
    python -m jarvis_phone type       # same, with a text dialog instead of the mic
    python -m jarvis_phone panel      # (re)post the sticky notification with Talk/Type buttons
    python -m jarvis_phone overlay speaking "hello"   # drive the floating ring by hand
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys

from . import __version__, termux
from .agent import Agent, Outgoing
from .config import settings
from .fortress import Fortress
from . import overlay, scheduler, voice
from .config import PHONE_DIR
from .telegram import TelegramBot

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s",
                    datefmt="%H:%M:%S")
log = logging.getLogger("jarvis")


async def _print(chat_id, out: Outgoing) -> None:
    print(f"{settings.name}: {out.text}" + (f"  [photo: {out.photo}]" if out.photo else ""))


async def cmd_chat(agent: Agent) -> None:
    print(f"{settings.name} v{__version__} — type a message, or 'quit'.")
    sched = asyncio.create_task(scheduler.run(agent, settings, _print, tick=5.0))
    loop = asyncio.get_event_loop()
    try:
        while True:
            try:
                text = await loop.run_in_executor(None, input, "you: ")
            except (EOFError, KeyboardInterrupt):
                break
            if text.strip().lower() in ("quit", "exit", "q"):
                break
            for out in await agent.handle(text, "local"):
                await _print("local", out)
                await agent.speak_aloud(out)
    finally:
        sched.cancel()


async def cmd_once(agent: Agent, text: str) -> None:
    for out in await agent.handle(text, "local"):
        await _print("local", out)


def post_panel() -> bool:
    """Sticky notification with Talk / Type buttons. Tapping runs the shortcut
    scripts the installer drops in ~/.shortcuts (Termux:Widget uses the same)."""
    home = os.path.expanduser("~")
    talk = f"{home}/.shortcuts/Jarvis"
    typ = f"{home}/.shortcuts/Jarvis-Type"
    if not (os.path.exists(talk) and os.path.exists(typ)):
        talk = f"cd {PHONE_DIR} && python -m jarvis_phone listen"
        typ = f"cd {PHONE_DIR} && python -m jarvis_phone type"
    try:
        termux.ongoing_notification(settings.name, "Tap to talk",
                                    [("🎤 Talk", talk), ("⌨️ Type", typ)])
        return True
    except termux.TermuxError as e:
        log.debug("panel unavailable: %s", e)
        return False


async def cmd_listen(agent: Agent, args: list[str], typed: bool = False) -> None:
    conversation = any(a in ("--conversation", "-c") for a in args)
    n = await voice.listen(agent, conversation=conversation, typed=typed)
    log.info("voice session: %d turn(s)", n)


async def cmd_bot(agent: Agent) -> None:
    if not settings.telegram_token:
        print("TELEGRAM_BOT_TOKEN is not set. Fill in phone/.env, or use "
              "`python -m jarvis_phone chat` for the terminal.")
        sys.exit(2)
    bot = TelegramBot(agent, settings)

    async def deliver(chat_id, out: Outgoing) -> None:
        # Reminders set from the terminal carry chat_id "local"; route those (and
        # the morning brief, which has none) to the owner's Telegram chat.
        target = chat_id if chat_id and chat_id.lstrip("-").isdigit() else bot.owner
        if target:
            await bot.deliver(target, out)
        else:
            await _print(chat_id, out)

    try:
        termux.wake_lock(True)
    except Exception:
        pass
    post_panel()
    await asyncio.gather(bot.run(), scheduler.run(agent, settings, deliver))


async def cmd_doctor(agent: Agent) -> None:
    def row(ok: bool | None, label: str, detail: str = "") -> None:
        mark = "✓" if ok else ("–" if ok is None else "✗")
        print(f"  {mark} {label:<18} {detail}")

    print(f"{settings.name} doctor\n")
    row(termux.available(), "Termux:API",
        "termux-battery-status found" if termux.available()
        else "not found — install the Termux:API app and `pkg install termux-api`")
    if termux.available():
        try:
            b = termux.battery()
            row(True, "battery", f"{b.get('percentage')}% — Termux:API app responds")
        except Exception as e:
            row(False, "battery", str(e))
        row(termux.available("termux-tts-speak"), "tts", "termux-tts-speak")
        row(termux.available("termux-dialog"), "voice input", "termux-dialog speech")
        row(overlay.available() or None, "overlay bridge",
            "`am` found — install phone/overlay APK for the floating ring" if overlay.available()
            else "`am` not found (pkg install termux-am)")
        home = os.path.expanduser("~")
        row(os.path.exists(f"{home}/.shortcuts/Jarvis") or None, "home shortcut",
            "~/.shortcuts/Jarvis (Termux:Widget)" if os.path.exists(f"{home}/.shortcuts/Jarvis")
            else "not installed — run setup-termux.sh")
    ok, msg = await agent.brain.healthy()
    row(ok, f"brain ({agent.brain.label})", msg)
    if settings.telegram_token:
        name = await TelegramBot(agent, settings).me()
        row(bool(name), "telegram", f"@{name}" if name else "token rejected / no network")
        row(bool(settings.telegram_chat_id), "owner chat", settings.telegram_chat_id
            or "TELEGRAM_CHAT_ID unset — send the bot /start to learn it")
    else:
        row(None, "telegram", "TELEGRAM_BOT_TOKEN unset (terminal-only mode)")
    f = Fortress()
    if f.configured:
        row(not (await f.status_text()).startswith("Couldn't"), "fortress", f.url)
    else:
        row(None, "fortress", "FORTRESS_URL unset — trading tools disabled")
    row(True, "data dir", str(agent.memory.path))
    print(f"\n  tools: {', '.join(agent.registry.names())}")


def main(argv: list[str] | None = None) -> None:
    argv = sys.argv[1:] if argv is None else argv
    cmd = argv[0] if argv else "bot"
    agent = Agent(settings)
    if cmd == "chat":
        asyncio.run(cmd_chat(agent))
    elif cmd == "doctor":
        asyncio.run(cmd_doctor(agent))
    elif cmd == "once":
        asyncio.run(cmd_once(agent, " ".join(argv[1:])))
    elif cmd == "listen":
        asyncio.run(cmd_listen(agent, argv[1:]))
    elif cmd == "type":
        asyncio.run(cmd_listen(agent, argv[1:], typed=True))
    elif cmd == "panel":
        print("panel posted" if post_panel() else "termux-notification not available")
    elif cmd == "overlay":
        state = argv[1] if len(argv) > 1 else "idle"
        text = " ".join(argv[2:]) or None
        ok = overlay.set_state(state, text)
        print(f"overlay → {state}" if ok else "overlay not reachable (is `am` available and the app installed?)")
    elif cmd in ("bot", "run"):
        asyncio.run(cmd_bot(agent))
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
