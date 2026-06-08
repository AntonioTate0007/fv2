"""
Thin async Telegram Bot API client — no SDK, just httpx against the HTTP API.

Exposes exactly what Jarvis needs: send a text message, send a spoken clip (voice
note when we can make OGG/Opus, audio file otherwise), and webhook management. All
calls fail soft (log + return False/None) so a Telegram outage never 500s the API.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

from . import tts

log = logging.getLogger("fortress.jarvis.telegram")

API = "https://api.telegram.org"


def bot_token() -> Optional[str]:
    return os.getenv("TELEGRAM_BOT_TOKEN") or None


def default_chat_id() -> Optional[str]:
    return os.getenv("TELEGRAM_CHAT_ID") or None


def is_configured() -> bool:
    return bool(bot_token())


def _client():
    import httpx
    return httpx.AsyncClient(timeout=30.0)


async def send_text(chat_id: str, text: str, *, markdown: bool = True) -> bool:
    token = bot_token()
    if not token or not chat_id:
        return False
    # Telegram caps messages at 4096 chars.
    if len(text) > 4096:
        text = text[:4093] + "…"
    payload = {"chat_id": chat_id, "text": text, "disable_web_page_preview": True}
    if markdown:
        payload["parse_mode"] = "Markdown"
    try:
        async with _client() as c:
            r = await c.post(f"{API}/bot{token}/sendMessage", json=payload)
            if r.status_code != 200:
                log.warning("[telegram] sendMessage %s: %s", r.status_code, r.text[:200])
            return r.status_code == 200
    except Exception as e:
        log.warning("[telegram] sendMessage error: %s", e)
        return False


async def send_voice(chat_id: str, text: str, *, caption: Optional[str] = None) -> bool:
    """Speak `text`. Sends a true voice note when ffmpeg can make OGG/Opus, else an
    MP3 audio file. Returns False (caller should fall back to text) when TTS is off."""
    token = bot_token()
    if not token or not chat_id:
        return False
    mp3 = tts.synthesize(text)
    if not mp3:
        return False

    ogg = tts.to_voice_ogg(mp3)
    try:
        async with _client() as c:
            if ogg:
                files = {"voice": ("jarvis.ogg", ogg, "audio/ogg")}
                data = {"chat_id": chat_id}
                if caption:
                    data["caption"] = caption[:1024]
                r = await c.post(f"{API}/bot{token}/sendVoice", data=data, files=files)
            else:
                files = {"audio": ("jarvis.mp3", mp3, "audio/mpeg")}
                data = {"chat_id": chat_id, "title": "Jarvis"}
                if caption:
                    data["caption"] = caption[:1024]
                r = await c.post(f"{API}/bot{token}/sendAudio", data=data, files=files)
            if r.status_code != 200:
                log.warning("[telegram] send voice/audio %s: %s", r.status_code, r.text[:200])
            return r.status_code == 200
    except Exception as e:
        log.warning("[telegram] send voice error: %s", e)
        return False


async def set_webhook(url: str, secret: Optional[str]) -> bool:
    token = bot_token()
    if not token:
        return False
    payload = {"url": url, "allowed_updates": ["message"]}
    if secret:
        payload["secret_token"] = secret
    try:
        async with _client() as c:
            r = await c.post(f"{API}/bot{token}/setWebhook", json=payload)
            ok = r.status_code == 200 and r.json().get("ok", False)
            log.info("[telegram] setWebhook → %s (%s)", ok, r.text[:160])
            return ok
    except Exception as e:
        log.warning("[telegram] setWebhook error: %s", e)
        return False


async def delete_webhook() -> bool:
    token = bot_token()
    if not token:
        return False
    try:
        async with _client() as c:
            r = await c.post(f"{API}/bot{token}/deleteWebhook")
            return r.status_code == 200
    except Exception as e:
        log.warning("[telegram] deleteWebhook error: %s", e)
        return False


async def get_updates(offset: int, timeout: int = 25) -> list[dict]:
    """Long-poll for updates (used only when no webhook is configured)."""
    token = bot_token()
    if not token:
        return []
    try:
        async with _client() as c:
            r = await c.post(
                f"{API}/bot{token}/getUpdates",
                json={"offset": offset, "timeout": timeout,
                      "allowed_updates": ["message"]},
                timeout=timeout + 10,
            )
            if r.status_code != 200:
                return []
            return r.json().get("result", []) or []
    except Exception as e:
        log.debug("[telegram] getUpdates error: %s", e)
        return []
