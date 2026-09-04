"""Settings, loaded from phone/.env (or the environment)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

PHONE_DIR = Path(__file__).resolve().parent.parent

try:
    from dotenv import load_dotenv
    load_dotenv(PHONE_DIR / ".env")
except Exception:  # dotenv is optional
    pass


def _on(v: str | None, default: bool = False) -> bool:
    if v is None or v == "":
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    telegram_token: str = ""
    telegram_chat_id: str = ""
    brain: str = "auto"
    local_llm_url: str = "http://127.0.0.1:8080/v1"
    local_llm_model: str = "local"
    local_llm_timeout: float = 60.0
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.0-flash"
    fortress_url: str = ""
    fortress_token: str = ""
    name: str = "Jarvis"
    speak: bool = False
    morning_brief: str = ""
    allow_sms: bool = False
    data_dir: Path = field(default_factory=lambda: Path("~/.jarvis").expanduser())

    @classmethod
    def from_env(cls) -> "Settings":
        e = os.getenv
        return cls(
            telegram_token=e("TELEGRAM_BOT_TOKEN", "").strip(),
            telegram_chat_id=e("TELEGRAM_CHAT_ID", "").strip(),
            brain=(e("JARVIS_BRAIN", "auto") or "auto").strip().lower(),
            local_llm_url=(e("LOCAL_LLM_URL", "http://127.0.0.1:8080/v1") or "").rstrip("/"),
            local_llm_model=e("LOCAL_LLM_MODEL", "local") or "local",
            local_llm_timeout=float(e("LOCAL_LLM_TIMEOUT", "60") or 60),
            gemini_api_key=e("GEMINI_API_KEY", "").strip(),
            gemini_model=e("GEMINI_MODEL", "gemini-2.0-flash") or "gemini-2.0-flash",
            fortress_url=(e("FORTRESS_URL", "") or "").strip().rstrip("/"),
            fortress_token=e("FORTRESS_API_TOKEN", "").strip(),
            name=e("JARVIS_NAME", "Jarvis") or "Jarvis",
            speak=_on(e("JARVIS_SPEAK")),
            morning_brief=(e("JARVIS_MORNING_BRIEF", "") or "").strip(),
            allow_sms=_on(e("JARVIS_ALLOW_SMS")),
            data_dir=Path(e("JARVIS_DATA_DIR", "~/.jarvis") or "~/.jarvis").expanduser(),
        )


settings = Settings.from_env()
