import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jarvis_phone import termux  # noqa: E402
from jarvis_phone.agent import Agent  # noqa: E402
from jarvis_phone.brain import RulesBrain  # noqa: E402
from jarvis_phone.config import Settings  # noqa: E402
from jarvis_phone.fortress import Fortress  # noqa: E402
from jarvis_phone.memory import Memory  # noqa: E402
from jarvis_phone.tools import build_registry  # noqa: E402

REAL_RUN = termux.run


class FakeTermux:
    """Records every termux-* invocation and returns canned output."""

    def __init__(self):
        self.calls: list[tuple] = []
        self.outputs = {
            "termux-battery-status": '{"percentage": 73, "status": "DISCHARGING", '
                                     '"temperature": 29.4, "plugged": "UNPLUGGED"}',
            "termux-wifi-connectioninfo": '{"ssid": "\\"HomeNet\\"", "ip": "192.168.1.20", '
                                          '"rssi": -55, "link_speed_mbps": 390}',
            "termux-location": '{"latitude": 51.5007, "longitude": -0.1246, "accuracy": 12.0}',
            "termux-clipboard-get": "hello from clipboard",
            "termux-volume": '[{"stream": "music", "volume": 5, "max_volume": 25}]',
        }

    def __call__(self, cmd, *args, timeout=15.0, stdin=None):
        self.calls.append((cmd, *args) if stdin is None else (cmd, *args, stdin))
        return self.outputs.get(cmd, "")

    def called(self, cmd):
        return [c for c in self.calls if c[0] == cmd]


@pytest.fixture
def fake_termux(monkeypatch):
    fake = FakeTermux()
    monkeypatch.setattr(termux, "run", fake)
    monkeypatch.setattr(termux.shutil, "which", lambda name: f"/usr/bin/{name}")
    return fake


@pytest.fixture
def memory(tmp_path):
    return Memory(tmp_path / "data")


@pytest.fixture
def agent(fake_termux, memory, tmp_path):
    s = Settings(brain="rules", data_dir=tmp_path / "data", allow_sms=True)
    fortress = Fortress(url="", token="")
    reg = build_registry(memory, fortress, s)
    return Agent(s, memory=memory, registry=reg, brain=RulesBrain(), fortress=fortress)
