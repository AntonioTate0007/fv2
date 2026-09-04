"""Entry points:

    python -m jarvis_phone            # Telegram bot + reminders (the daemon)
    python -m jarvis_phone chat       # talk to Jarvis in the terminal
    python -m jarvis_phone doctor     # check Termux:API, model, Telegram, Fortress
    python -m jarvis_phone once "battery"   # run one message and exit
"""

from __future__ import annotations

import asyncio
import logging
import sys

from . import __version__, termux
from .agent import Agent, Outgoing
from .config import settings
from .fortress import Fortress
from . import scheduler
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
    elif cmd in ("bot", "run"):
        asyncio.run(cmd_bot(agent))
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
