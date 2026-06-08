"""
Jarvis — the personal trading assistant that talks to the operator over Telegram
with text + voice. Inspired by OpenJarvis' scheduled, TTS-narrated digest agents.

  • telegram.py  — async Telegram Bot API client (send text/voice, webhook, polling)
  • tts.py       — text→speech (ElevenLabs → gTTS → none), OGG/Opus voice notes
  • assistant.py — the persona: briefings, command routing, Gemini Q&A
"""

from . import assistant, telegram, tts

__all__ = ["assistant", "telegram", "tts"]
