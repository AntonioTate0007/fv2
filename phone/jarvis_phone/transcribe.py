"""Speech-to-text for Telegram voice notes. Gemini accepts the OGG/Opus bytes
directly, so no ffmpeg and no extra model on the phone."""

from __future__ import annotations

import base64
import logging
from typing import Optional

import httpx

log = logging.getLogger("jarvis.transcribe")

PROMPT = ("Transcribe this voice message verbatim. Output only the transcript, "
          "no quotes, no commentary. If it is empty or unintelligible, output nothing.")


class GeminiTranscriber:
    API = "https://generativelanguage.googleapis.com/v1beta/models"

    def __init__(self, api_key: str, model: str = "gemini-2.0-flash"):
        self.api_key = api_key
        self.model = model

    @property
    def available(self) -> bool:
        return bool(self.api_key)

    async def transcribe(self, audio: bytes, mime: str = "audio/ogg") -> Optional[str]:
        if not self.api_key:
            return None
        body = {
            "contents": [{"role": "user", "parts": [
                {"text": PROMPT},
                {"inlineData": {"mimeType": mime,
                                "data": base64.b64encode(audio).decode("ascii")}},
            ]}],
            "generationConfig": {"temperature": 0.0, "maxOutputTokens": 400},
        }
        try:
            async with httpx.AsyncClient(timeout=60.0) as c:
                r = await c.post(f"{self.API}/{self.model}:generateContent",
                                 params={"key": self.api_key}, json=body)
                r.raise_for_status()
                parts = r.json()["candidates"][0]["content"]["parts"]
                return "".join(p.get("text", "") for p in parts).strip() or None
        except Exception as e:
            log.warning("transcription failed: %s", e)
            return None
