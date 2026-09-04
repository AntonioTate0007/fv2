"""Tiny JSON-backed store for chat history, notes and reminders."""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any

MAX_HISTORY = 12


class Memory:
    def __init__(self, data_dir: Path):
        self.path = Path(data_dir).expanduser()
        self.path.mkdir(parents=True, exist_ok=True)
        self.file = self.path / "memory.json"
        self._lock = threading.Lock()
        self._data: dict[str, Any] = {"history": [], "notes": [], "reminders": [],
                                      "prefs": {}}
        self._load()

    # ── persistence ───────────────────────────────────────────────────────
    def _load(self) -> None:
        if self.file.exists():
            try:
                self._data.update(json.loads(self.file.read_text()))
            except Exception:
                pass

    def _save(self) -> None:
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, indent=1))
        tmp.replace(self.file)

    # ── chat history (for LLM context) ────────────────────────────────────
    def remember(self, role: str, text: str) -> None:
        with self._lock:
            h = self._data["history"]
            h.append({"role": role, "text": text[:600], "t": time.time()})
            del h[:-MAX_HISTORY]
            self._save()

    def history(self) -> list[dict]:
        return list(self._data["history"])

    def clear_history(self) -> None:
        with self._lock:
            self._data["history"] = []
            self._save()

    # ── notes ─────────────────────────────────────────────────────────────
    def add_note(self, text: str) -> int:
        with self._lock:
            self._data["notes"].append({"text": text.strip(), "t": time.time()})
            self._save()
            return len(self._data["notes"])

    def notes(self) -> list[dict]:
        return list(self._data["notes"])

    def clear_notes(self) -> int:
        with self._lock:
            n = len(self._data["notes"])
            self._data["notes"] = []
            self._save()
            return n

    # ── reminders ─────────────────────────────────────────────────────────
    def add_reminder(self, when: float, text: str, chat_id: str | None) -> dict:
        r = {"id": int(time.time() * 1000) % 10_000_000, "when": when,
             "text": text.strip(), "chat_id": chat_id}
        with self._lock:
            self._data["reminders"].append(r)
            self._save()
        return r

    def reminders(self) -> list[dict]:
        return sorted(self._data["reminders"], key=lambda r: r["when"])

    def pop_due(self, now: float | None = None) -> list[dict]:
        now = time.time() if now is None else now
        with self._lock:
            due = [r for r in self._data["reminders"] if r["when"] <= now]
            if due:
                self._data["reminders"] = [r for r in self._data["reminders"]
                                           if r["when"] > now]
                self._save()
        return due

    def cancel_reminders(self) -> int:
        with self._lock:
            n = len(self._data["reminders"])
            self._data["reminders"] = []
            self._save()
            return n

    # ── prefs ─────────────────────────────────────────────────────────────
    def pref(self, key: str, default: Any = None) -> Any:
        return self._data["prefs"].get(key, default)

    def set_pref(self, key: str, value: Any) -> None:
        with self._lock:
            self._data["prefs"][key] = value
            self._save()
