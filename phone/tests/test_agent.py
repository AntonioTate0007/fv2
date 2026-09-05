import time

import pytest

from jarvis_phone.tools import parse_clock, parse_duration


@pytest.mark.asyncio
async def test_battery_reads_termux(agent, fake_termux):
    out = await agent.handle("battery")
    assert "73%" in out[0].text
    assert fake_termux.called("termux-battery-status")


@pytest.mark.asyncio
async def test_torch_on_off(agent, fake_termux):
    await agent.handle("flashlight on")
    await agent.handle("turn the torch off")
    assert fake_termux.called("termux-torch") == [("termux-torch", "on"), ("termux-torch", "off")]


@pytest.mark.asyncio
async def test_brightness_scaled_to_255(agent, fake_termux):
    await agent.handle("brightness 50%")
    assert fake_termux.called("termux-brightness") == [("termux-brightness", "128")]


@pytest.mark.asyncio
async def test_volume_uses_stream_max(agent, fake_termux):
    out = await agent.handle("volume 40%")
    assert fake_termux.called("termux-volume")[-1] == ("termux-volume", "music", "10")
    assert "40%" in out[0].text


@pytest.mark.asyncio
async def test_photo_returns_attachment(agent, fake_termux):
    out = await agent.handle("selfie")
    assert out[0].photo and out[0].photo.endswith(".jpg")
    assert fake_termux.called("termux-camera-photo")[0][2] == "1"


@pytest.mark.asyncio
async def test_speak_tool_does_not_double_speak(agent, fake_termux):
    out = await agent.handle("say dinner is ready")
    assert fake_termux.called("termux-tts-speak")
    assert out[0].speak == ""


@pytest.mark.asyncio
async def test_reminder_roundtrip(agent, memory):
    out = await agent.handle("remind me in 10 minutes to call mom", chat_id="42")
    assert "call mom" in out[0].text
    rs = memory.reminders()
    assert len(rs) == 1 and rs[0]["chat_id"] == "42"
    assert 590 < rs[0]["when"] - time.time() <= 600
    assert memory.pop_due(now=time.time()) == []
    due = memory.pop_due(now=time.time() + 601)
    assert due and due[0]["text"] == "call mom"
    assert memory.reminders() == []


@pytest.mark.asyncio
async def test_notes(agent):
    await agent.handle("note: buy milk")
    out = await agent.handle("show my notes")
    assert "buy milk" in out[0].text


@pytest.mark.asyncio
async def test_confirm_flow_yes(agent, fake_termux):
    out = await agent.handle("text +15551234567: running late", chat_id="1")
    assert "yes" in out[0].text.lower()
    assert not fake_termux.called("termux-sms-send")
    out = await agent.handle("yes", chat_id="1")
    assert fake_termux.called("termux-sms-send") == [
        ("termux-sms-send", "-n", "+15551234567", "running late")]
    assert "Sent" in out[0].text


@pytest.mark.asyncio
async def test_confirm_flow_no(agent, fake_termux):
    await agent.handle("clear my notes", chat_id="1")
    out = await agent.handle("no", chat_id="1")
    assert out[0].text == "Cancelled."


@pytest.mark.asyncio
async def test_confirm_is_per_chat(agent, fake_termux):
    await agent.handle("clear my notes", chat_id="1")
    out = await agent.handle("yes", chat_id="2")   # different chat: nothing pending
    assert "Deleted" not in out[0].text


@pytest.mark.asyncio
async def test_fortress_unlinked_message(agent):
    out = await agent.handle("how am I doing")
    assert "FORTRESS_URL" in out[0].text


@pytest.mark.asyncio
async def test_missing_termux_is_soft(agent, monkeypatch):
    from jarvis_phone import termux
    from conftest import REAL_RUN
    monkeypatch.setattr(termux, "run", REAL_RUN)
    monkeypatch.setattr(termux.shutil, "which", lambda name: None)
    out = await agent.handle("battery")
    assert "Phone says no" in out[0].text


@pytest.mark.asyncio
async def test_unknown_gets_fallback(agent):
    out = await agent.handle("what's the capital of peru")
    assert "/help" in out[0].text


def test_parse_duration():
    assert parse_duration("in 10 minutes") == 600
    assert parse_duration("2h") == 7200
    assert parse_duration("1 hour 30 min") == 5400
    assert parse_duration("45s") == 45
    assert parse_duration("tomorrow") is None


def test_parse_clock():
    import datetime as dt
    now = dt.datetime(2026, 9, 4, 10, 0)
    assert parse_clock("at 7:30pm", now) == dt.datetime(2026, 9, 4, 19, 30)
    assert parse_clock("at 9", now) == dt.datetime(2026, 9, 5, 9, 0)      # already past → tomorrow
    assert parse_clock("12am", now) == dt.datetime(2026, 9, 5, 0, 0)
    assert parse_clock("soon", now) is None
