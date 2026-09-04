import json

from jarvis_phone import overlay, voice


def capture(monkeypatch):
    sent = []
    monkeypatch.setattr(overlay, "available", lambda: True)
    monkeypatch.setattr(overlay, "_run", lambda argv: (sent.append(argv), True)[1])
    return sent


def test_broadcast_shape(monkeypatch):
    sent = capture(monkeypatch)
    assert overlay.speaking("Battery is at 73%")
    argv = sent[0]
    assert argv[:2] == ["am", "broadcast"]
    assert "-n" in argv and overlay.COMPONENT in argv
    assert "-a" in argv and overlay.ACTION in argv
    assert argv[argv.index("state") + 1] == "speaking"
    assert argv[argv.index("text") + 1] == "Battery is at 73%"


def test_noop_without_am(monkeypatch):
    monkeypatch.setattr(overlay, "available", lambda: False)
    assert overlay.speaking("x") is False and overlay.idle() is False


def test_rejects_unknown_state():
    import pytest
    with pytest.raises(ValueError):
        overlay.set_state("dancing")


def test_speaking_while_always_returns_to_idle(monkeypatch):
    sent = capture(monkeypatch)
    try:
        with overlay.speaking_while("hi"):
            raise RuntimeError("tts died")
    except RuntimeError:
        pass
    states = [a[a.index("state") + 1] for a in sent]
    assert states == ["speaking", "idle"]


async def test_voice_session_drives_ring(agent, fake_termux, monkeypatch):
    from jarvis_phone import termux
    sent = capture(monkeypatch)

    def run(cmd, *args, timeout=15.0, stdin=None):
        fake_termux.calls.append((cmd, *args))
        if cmd == "termux-dialog":
            return json.dumps({"code": 0, "text": "battery"})
        return fake_termux.outputs.get(cmd, "")

    monkeypatch.setattr(termux, "run", run)
    await voice.listen(agent)
    states = [a[a.index("state") + 1] for a in sent]
    assert states == ["listening", "thinking", "speaking", "idle", "idle"]
    caption = sent[2][sent[2].index("text") + 1]
    assert "73%" in caption
