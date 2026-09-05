import shutil
import subprocess
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent.parent


@pytest.fixture
def phone_dir(tmp_path):
    shutil.copy(HERE / "configure.sh", tmp_path / "configure.sh")
    shutil.copy(HERE / ".env.example", tmp_path / ".env.example")
    return tmp_path


def run(phone_dir, *args):
    return subprocess.run(["bash", str(phone_dir / "configure.sh"), *args],
                          capture_output=True, text=True, timeout=20)


def test_creates_env_and_sets_keys(phone_dir):
    r = run(phone_dir, "TELEGRAM_BOT_TOKEN=123:abc/def", "TELEGRAM_CHAT_ID=42")
    assert r.returncode == 0, r.stderr
    env = (phone_dir / ".env").read_text()
    assert "TELEGRAM_BOT_TOKEN=123:abc/def\n" in env
    assert "TELEGRAM_CHAT_ID=42\n" in env
    assert env.count("TELEGRAM_BOT_TOKEN=") == 1          # replaced, not duplicated
    assert "updated: TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID" in r.stdout
    assert "123:abc" not in r.stdout                       # never echoes values


def test_appends_unknown_key_and_clears(phone_dir):
    run(phone_dir, "NEW_KEY=hello")
    assert "NEW_KEY=hello\n" in (phone_dir / ".env").read_text()
    run(phone_dir, "TELEGRAM_BOT_TOKEN=")
    assert "TELEGRAM_BOT_TOKEN=\n" in (phone_dir / ".env").read_text()


def test_rejects_bad_key(phone_dir):
    r = run(phone_dir, "rm -rf=oops", "GEMINI_API_KEY=k")
    assert r.returncode == 0
    assert "bad key" in r.stderr
    env = (phone_dir / ".env").read_text()
    assert "oops" not in env and "GEMINI_API_KEY=k\n" in env
