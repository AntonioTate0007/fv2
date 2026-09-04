"""Long-polling Telegram front end. No SDK, just httpx against the Bot API.

Only `TELEGRAM_CHAT_ID` can talk to the agent; anyone else gets a polite refusal
plus their own chat id so the owner can set it up."""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

import httpx

from .agent import Agent, Outgoing
from .config import Settings
from .transcribe import GeminiTranscriber

log = logging.getLogger("jarvis.telegram")
API = "https://api.telegram.org"

HELP = """*{name}* — your phone's local assistant.

Just talk to me. Things I understand:
• battery · wifi · where am I · device status · what time is it
• flashlight on/off · vibrate · brightness 40% · volume 20% · mute
• say hello there · notify me: drink water · copy this: text · clipboard
• take a photo · selfie · open youtube.com
• remind me in 20 minutes to call mom · reminders · cancel reminders
• note: buy milk · notes · clear notes
• how am I doing · positions · run the swarm (needs Fortress)

Send a *voice note* and I'll transcribe it (needs GEMINI_API_KEY).

Commands: /help /tools /brain /speak on|off /forget /whoami"""


class TelegramBot:
    def __init__(self, agent: Agent, s: Settings):
        self.agent = agent
        self.s = s
        self.token = s.telegram_token
        self.owner = s.telegram_chat_id
        self._offset = 0
        self.transcriber = GeminiTranscriber(s.gemini_api_key, s.gemini_model)

    # ── outbound ──────────────────────────────────────────────────────────
    async def send_text(self, chat_id: str, text: str, markdown: bool = True) -> bool:
        if len(text) > 4096:
            text = text[:4093] + "…"
        payload = {"chat_id": chat_id, "text": text, "disable_web_page_preview": True}
        if markdown:
            payload["parse_mode"] = "Markdown"
        try:
            async with httpx.AsyncClient(timeout=30.0) as c:
                r = await c.post(f"{API}/bot{self.token}/sendMessage", json=payload)
                if r.status_code != 200 and markdown:
                    # bad markdown (e.g. a stray underscore) → resend plain
                    payload.pop("parse_mode")
                    r = await c.post(f"{API}/bot{self.token}/sendMessage", json=payload)
                if r.status_code != 200:
                    log.warning("sendMessage %s: %s", r.status_code, r.text[:200])
                return r.status_code == 200
        except Exception as e:
            log.warning("sendMessage error: %s", e)
            return False

    async def send_photo(self, chat_id: str, path: str, caption: str = "") -> bool:
        try:
            with open(path, "rb") as f:
                files = {"photo": ("photo.jpg", f.read(), "image/jpeg")}
            async with httpx.AsyncClient(timeout=60.0) as c:
                r = await c.post(f"{API}/bot{self.token}/sendPhoto",
                                 data={"chat_id": chat_id, "caption": caption[:1024]},
                                 files=files)
                return r.status_code == 200
        except Exception as e:
            log.warning("sendPhoto error: %s", e)
            return False

    async def deliver(self, chat_id: str, out: Outgoing) -> None:
        if out.photo:
            await self.send_photo(chat_id, out.photo, out.text)
        elif out.text:
            await self.send_text(chat_id, out.text)
        await self.agent.speak_aloud(out)

    async def notify_owner(self, out: Outgoing) -> None:
        if self.owner:
            await self.deliver(self.owner, out)

    # ── inbound ───────────────────────────────────────────────────────────
    async def get_updates(self) -> list[dict]:
        try:
            async with httpx.AsyncClient(timeout=40.0) as c:
                r = await c.get(f"{API}/bot{self.token}/getUpdates",
                                params={"offset": self._offset, "timeout": 25,
                                        "allowed_updates": '["message"]'})
                if r.status_code != 200:
                    log.warning("getUpdates %s: %s", r.status_code, r.text[:200])
                    await asyncio.sleep(5)
                    return []
                return r.json().get("result", [])
        except Exception as e:
            log.warning("getUpdates error: %s", e)
            await asyncio.sleep(5)
            return []

    async def download(self, file_id: str) -> Optional[bytes]:
        try:
            async with httpx.AsyncClient(timeout=60.0) as c:
                r = await c.get(f"{API}/bot{self.token}/getFile", params={"file_id": file_id})
                r.raise_for_status()
                path = r.json()["result"]["file_path"]
                f = await c.get(f"{API}/file/bot{self.token}/{path}")
                f.raise_for_status()
                return f.content
        except Exception as e:
            log.warning("download failed: %s", e)
            return None

    async def me(self) -> Optional[str]:
        try:
            async with httpx.AsyncClient(timeout=15.0) as c:
                r = await c.get(f"{API}/bot{self.token}/getMe")
                if r.status_code == 200:
                    return r.json()["result"].get("username")
        except Exception:
            pass
        return None

    async def handle_update(self, update: dict) -> None:
        msg = update.get("message") or update.get("edited_message")
        if not msg:
            return
        chat_id = str(msg.get("chat", {}).get("id", ""))
        text = (msg.get("text") or msg.get("caption") or "").strip()
        voice = msg.get("voice") or msg.get("audio")
        if not chat_id or not (text or voice):
            return
        if voice and not text:
            if chat_id != self.owner:
                await self.send_text(chat_id, "I only take orders from my principal.")
                return
            text = await self._transcribe(chat_id, voice)
            if not text:
                return
        cmd, _, arg = text.partition(" ")
        cmd = cmd.lower().lstrip("/").split("@")[0]
        arg = arg.strip()

        if cmd in ("start", "help"):
            note = ""
            if not self.owner:
                note = (f"\n\n_Your chat id is_ `{chat_id}` — put it in TELEGRAM_CHAT_ID "
                        "so I take your commands.")
            await self.send_text(chat_id, HELP.format(name=self.s.name) + note)
            return
        if cmd == "whoami":
            await self.send_text(chat_id, f"Chat id `{chat_id}`"
                                 f"{' (owner)' if chat_id == self.owner else ''}")
            return
        if chat_id != self.owner:
            await self.send_text(chat_id, "I only take orders from my principal. "
                                          f"(Your chat id: `{chat_id}`)")
            return

        if cmd == "tools":
            lines = [f"• *{t.name}* — {t.description}" for t in self.agent.registry.all()]
            await self.send_text(chat_id, "*Tools*\n" + "\n".join(lines))
            return
        if cmd == "brain":
            ok, msg = await self.agent.brain.healthy()
            await self.send_text(chat_id, f"Brain `{self.agent.brain.label}`: {msg}",
                                 markdown=False)
            return
        if cmd == "speak":
            if arg.lower() in ("on", "off"):
                self.agent.set_speak(arg.lower() == "on")
            await self.send_text(chat_id, f"Speaking replies aloud: "
                                          f"*{'on' if self.agent.speak else 'off'}*")
            return
        if cmd == "forget":
            self.agent.memory.clear_history()
            await self.send_text(chat_id, "Short-term memory wiped.")
            return

        for out in await self.agent.handle(text, chat_id):
            await self.deliver(chat_id, out)

    async def _transcribe(self, chat_id: str, voice: dict) -> str:
        if not self.transcriber.available:
            await self.send_text(chat_id, "I can't hear voice notes without GEMINI_API_KEY "
                                          "— type it instead.")
            return ""
        audio = await self.download(voice.get("file_id", ""))
        if not audio:
            await self.send_text(chat_id, "Couldn't download that voice note.")
            return ""
        text = await self.transcriber.transcribe(audio, voice.get("mime_type") or "audio/ogg")
        if not text:
            await self.send_text(chat_id, "I couldn't make out that voice note.")
            return ""
        await self.send_text(chat_id, f"🎤 _{text}_")
        return text

    async def run(self) -> None:
        name = await self.me()
        log.info("Telegram bot @%s polling (owner chat %s)", name or "?",
                 self.owner or "UNSET — send /start to learn your id")
        while True:
            for u in await self.get_updates():
                self._offset = u["update_id"] + 1
                try:
                    await self.handle_update(u)
                except Exception as e:
                    log.exception("update failed: %s", e)
