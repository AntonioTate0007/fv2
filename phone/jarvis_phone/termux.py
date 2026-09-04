"""Thin wrappers around the Termux:API command-line tools.

Every function shells out to a `termux-*` binary (provided by the Termux:API
package + app) and fails soft: when the binary is missing or times out you get a
`TermuxError` with a message the assistant can relay verbatim. Nothing here
imports anything Android-specific, so the whole package runs on a laptop for
tests — the tools just report that the phone isn't reachable.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from typing import Any, Sequence

DEFAULT_TIMEOUT = 15.0


class TermuxError(RuntimeError):
    pass


def available(cmd: str = "termux-battery-status") -> bool:
    return shutil.which(cmd) is not None


def run(cmd: str, *args: str, timeout: float = DEFAULT_TIMEOUT,
        stdin: str | None = None) -> str:
    """Run a termux-* command and return stdout. Raises TermuxError on failure."""
    if shutil.which(cmd) is None:
        raise TermuxError(
            f"{cmd} isn't installed. Install the Termux:API app and run "
            "`pkg install termux-api`.")
    try:
        p = subprocess.run([cmd, *args], capture_output=True, text=True,
                           timeout=timeout, input=stdin)
    except subprocess.TimeoutExpired:
        raise TermuxError(f"{cmd} timed out after {timeout:.0f}s — is the "
                          "Termux:API app installed and allowed to run?")
    if p.returncode != 0:
        err = (p.stderr or p.stdout or "").strip()
        raise TermuxError(f"{cmd} failed: {err or 'exit ' + str(p.returncode)}")
    return p.stdout


def run_json(cmd: str, *args: str, timeout: float = DEFAULT_TIMEOUT) -> Any:
    out = run(cmd, *args, timeout=timeout).strip()
    if not out:
        return {}
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        raise TermuxError(f"{cmd} returned something that isn't JSON: {out[:120]}")


# ── Read-only sensors ──────────────────────────────────────────────────────────

def battery() -> dict:
    return run_json("termux-battery-status")


def location(provider: str = "gps", request: str = "once") -> dict:
    # GPS fix can take a while outdoors; fall back to network positioning.
    try:
        return run_json("termux-location", "-p", provider, "-r", request, timeout=45)
    except TermuxError:
        if provider == "gps":
            return run_json("termux-location", "-p", "network", "-r", request, timeout=30)
        raise


def wifi() -> dict:
    return run_json("termux-wifi-connectioninfo")


def clipboard_get() -> str:
    return run("termux-clipboard-get")


def device_info() -> dict:
    return run_json("termux-telephony-deviceinfo")


# ── Actions ────────────────────────────────────────────────────────────────────

def torch(on: bool) -> None:
    run("termux-torch", "on" if on else "off")


def vibrate(ms: int = 400) -> None:
    run("termux-vibrate", "-d", str(max(50, min(int(ms), 5000))), "-f")


def notify(title: str, content: str, notif_id: str = "jarvis") -> None:
    run("termux-notification", "--id", notif_id, "--title", title, "--content", content)


def speak(text: str, rate: float = 1.0) -> None:
    run("termux-tts-speak", "-r", str(rate), text, timeout=60)


def clipboard_set(text: str) -> None:
    run("termux-clipboard-set", stdin=text)


def brightness(level: int) -> None:
    run("termux-brightness", str(max(0, min(int(level), 255))))


def volume(stream: str, level: int) -> None:
    run("termux-volume", stream, str(max(0, int(level))))


def volumes() -> list[dict]:
    data = run_json("termux-volume")
    return data if isinstance(data, list) else []


def photo(path: str, camera: str = "0") -> str:
    run("termux-camera-photo", "-c", camera, path, timeout=30)
    return path


def sms_send(number: str, text: str) -> None:
    run("termux-sms-send", "-n", number, text)


def toast(text: str) -> None:
    run("termux-toast", "-s", text)


def open_url(url: str) -> None:
    run("termux-open-url", url)


def wake_lock(on: bool) -> None:
    run("termux-wake-lock" if on else "termux-wake-unlock")


def shell(argv: Sequence[str], timeout: float = 10.0) -> str:
    """Run a plain (non-termux) command, e.g. `df`, `uptime`."""
    if shutil.which(argv[0]) is None:
        raise TermuxError(f"{argv[0]} is not available")
    p = subprocess.run(list(argv), capture_output=True, text=True, timeout=timeout)
    return p.stdout.strip()
