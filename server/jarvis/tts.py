"""
Text-to-speech for the Jarvis assistant.

Produces an audio clip (bytes) from text so the bot can send spoken briefings.
Degrades gracefully:

  1. ElevenLabs (premium voice) when ELEVENLABS_API_KEY is set.
  2. gTTS (Google Translate TTS, free, no key) otherwise.
  3. None — caller falls back to text-only.

Output is MP3. `to_voice_ogg()` converts to OGG/Opus via ffmpeg when available so
Telegram renders a true voice note; otherwise the caller sends it as an audio file.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
from typing import Optional

log = logging.getLogger("fortress.jarvis.tts")

ELEVEN_VOICE = os.getenv("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")  # "Rachel"


def synthesize(text: str) -> Optional[bytes]:
    """Return MP3 bytes for `text`, or None if no TTS path is available."""
    text = (text or "").strip()
    if not text:
        return None
    # Telegram TTS clips are short by nature; keep it sane.
    if len(text) > 2500:
        text = text[:2497] + "…"

    key = os.getenv("ELEVENLABS_API_KEY")
    if key:
        audio = _elevenlabs(text, key)
        if audio:
            return audio
        log.info("[tts] ElevenLabs failed — falling back to gTTS")
    return _gtts(text)


def _elevenlabs(text: str, key: str) -> Optional[bytes]:
    try:
        import httpx
        url = f"https://api.elevenlabs.io/v1/text-to-speech/{ELEVEN_VOICE}"
        r = httpx.post(
            url,
            headers={"xi-api-key": key, "accept": "audio/mpeg"},
            json={"text": text, "model_id": "eleven_turbo_v2",
                  "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}},
            timeout=30.0,
        )
        if r.status_code == 200 and r.content:
            return r.content
        log.warning("[tts] ElevenLabs HTTP %s: %s", r.status_code, r.text[:200])
    except Exception as e:
        log.warning("[tts] ElevenLabs error: %s", e)
    return None


def _gtts(text: str) -> Optional[bytes]:
    try:
        import io
        from gtts import gTTS
        buf = io.BytesIO()
        gTTS(text=text, lang="en").write_to_fp(buf)
        return buf.getvalue()
    except Exception as e:
        log.warning("[tts] gTTS unavailable/failed: %s", e)
        return None


def to_voice_ogg(mp3: bytes) -> Optional[bytes]:
    """Transcode MP3 → OGG/Opus for a Telegram voice note. Needs ffmpeg on PATH;
    returns None when ffmpeg is absent so the caller sends an audio file instead."""
    if not mp3 or not shutil.which("ffmpeg"):
        return None
    try:
        proc = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error",
             "-i", "pipe:0", "-c:a", "libopus", "-b:a", "32k", "-f", "ogg", "pipe:1"],
            input=mp3, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30,
        )
        if proc.returncode == 0 and proc.stdout:
            return proc.stdout
        log.warning("[tts] ffmpeg ogg transcode failed: %s", proc.stderr[:200])
    except Exception as e:
        log.warning("[tts] ffmpeg error: %s", e)
    return None
