"""Drives the floating reactor ring (phone/overlay, a tiny separate Android app).

Jarvis tells the ring what it is doing with an Android broadcast sent through
Termux's `am` command. Nothing here is required: without the app (or without
`am`) every call is a silent no-op.

    idle · listening · thinking · speaking (+ caption) · hidden · shown · stop
"""

from __future__ import annotations

import logging
import shutil
import subprocess
from contextlib import contextmanager
from typing import Optional

log = logging.getLogger("jarvis.overlay")

COMPONENT = "com.fortress.jarvis.overlay/.StateReceiver"
ACTION = "com.fortress.jarvis.overlay.STATE"
STATES = ("idle", "listening", "thinking", "speaking", "hidden", "shown", "stop")


def available() -> bool:
    return shutil.which("am") is not None


def _run(argv: list[str]) -> bool:
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=5)
        return p.returncode == 0
    except Exception as e:  # never let the HUD break the assistant
        log.debug("overlay broadcast failed: %s", e)
        return False


def set_state(state: str, text: Optional[str] = None) -> bool:
    if state not in STATES:
        raise ValueError(f"unknown overlay state {state!r}")
    if not available():
        return False
    argv = ["am", "broadcast", "-n", COMPONENT, "-a", ACTION, "--es", "state", state]
    if text:
        argv += ["--es", "text", text.strip()[:160]]
    return _run(argv)


def idle() -> bool: return set_state("idle")
def listening() -> bool: return set_state("listening")
def thinking() -> bool: return set_state("thinking")
def speaking(text: Optional[str] = None) -> bool: return set_state("speaking", text)


@contextmanager
def speaking_while(text: Optional[str] = None):
    """`with overlay.speaking_while(text): termux.speak(text)` — ring pulses for
    exactly as long as the phone is talking, then goes translucent again."""
    speaking(text)
    try:
        yield
    finally:
        idle()
