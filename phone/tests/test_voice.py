import json

import httpx

from jarvis_phone import voice
from jarvis_phone.memory import Memory
from jarvis_phone.telegram import TelegramBot
from jarvis_phone.transcribe import GeminiTranscriber


def speech(fake, *texts):
    """Queue what the speech dialog 'hears' on successive calls."""
    q = list(texts)

    def out(cmd, *args, timeout=15.0, stdin=None):
        fake.calls.append((cmd, *args))
        if cmd == "termux-dialog":
            t = q.pop(0) if q else ""
            return json.dumps({"code": 0, "text": t}) if t else json.dumps({"code": -2})
        return fake.outputs.get(cmd, "")
    return out


async def test_listen_runs_tool_and_speaks(agent, fake_termux, monkeypatch):
    from jarvis_phone import termux
    monkeypatch.setattr(termux, "run", speech(fake_termux, "turn on the flashlight"))
    n = await voice.listen(agent)
    assert n == 1
    assert ("termux-torch", "on") in fake_termux.calls
    said = [c for c in fake_termux.calls if c[0] == "termux-tts-speak"]
    assert said and "Flashlight on" in said[-1][-1]


async def test_listen_nothing_heard(agent, fake_termux, monkeypatch):
    from jarvis_phone import termux
    monkeypatch.setattr(termux, "run", speech(fake_termux))   # dialog cancelled
    assert await voice.listen(agent) == 0
    said = [c for c in fake_termux.calls if c[0] == "termux-tts-speak"]
    assert said and "didn't catch" in said[-1][-1]


async def test_conversation_until_bye(agent, fake_termux, monkeypatch):
    from jarvis_phone import termux
    monkeypatch.setattr(termux, "run", speech(fake_termux, "battery", "what time is it", "ok bye"))
    n = await voice.listen(agent, conversation=True)
    assert n == 3
    assert fake_termux.called("termux-battery-status")
    said = [c[-1] for c in fake_termux.calls if c[0] == "termux-tts-speak"]
    assert said[-1] == "Goodbye."


async def test_voice_confirmation_keeps_mic_open(agent, fake_termux, monkeypatch):
    from jarvis_phone import termux
    monkeypatch.setattr(termux, "run", speech(fake_termux, "text +15551234567: on my way", "yes"))
    n = await voice.listen(agent)          # not conversation mode, but confirm pending
    assert n == 2
    assert ("termux-sms-send", "-n", "+15551234567", "on my way") in fake_termux.calls


def test_memory_shared_across_processes(tmp_path):
    a = Memory(tmp_path)
    b = Memory(tmp_path)                    # second "process"
    import os, time
    a.add_reminder(time.time() + 60, "from voice", "voice")
    # make sure the mtime differs on coarse filesystems
    os.utime(a.file, (time.time() + 2, time.time() + 2))
    assert [r["text"] for r in b.reminders()] == ["from voice"]
    b.add_note("seen by daemon")
    os.utime(b.file, (time.time() + 4, time.time() + 4))
    assert [n["text"] for n in a.notes()] == ["seen by daemon"]


async def test_gemini_transcriber_shape(monkeypatch):
    seen = {}

    def handler(req: httpx.Request):
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [
            {"text": " remind me in five minutes to stretch "}]}}]})

    real = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient",
                        lambda *a, **kw: real(*a, transport=httpx.MockTransport(handler), **kw))
    text = await GeminiTranscriber("k").transcribe(b"OggS...", "audio/ogg")
    assert text == "remind me in five minutes to stretch"
    part = seen["body"]["contents"][0]["parts"][1]["inlineData"]
    assert part["mimeType"] == "audio/ogg" and part["data"]


async def test_transcriber_without_key():
    assert not GeminiTranscriber("").available
    assert await GeminiTranscriber("").transcribe(b"x") is None


async def test_telegram_voice_note_routes_to_agent(agent, fake_termux, monkeypatch):
    s = agent.settings
    s.telegram_token = "t"
    s.telegram_chat_id = "7"
    s.gemini_api_key = "k"
    bot = TelegramBot(agent, s)
    sent = []

    async def fake_send(chat_id, text, markdown=True):
        sent.append(text)
        return True

    async def fake_download(file_id):
        return b"OggS"

    async def fake_transcribe(audio, mime="audio/ogg"):
        return "turn on the torch"

    monkeypatch.setattr(bot, "send_text", fake_send)
    monkeypatch.setattr(bot, "download", fake_download)
    monkeypatch.setattr(bot.transcriber, "transcribe", fake_transcribe)
    await bot.handle_update({"message": {"chat": {"id": 7},
                                         "voice": {"file_id": "f1", "mime_type": "audio/ogg"}}})
    assert sent[0].startswith("🎤") and "turn on the torch" in sent[0]
    assert ("termux-torch", "on") in fake_termux.calls
    assert "Flashlight on." in sent[-1]


async def test_telegram_voice_note_without_key(agent, monkeypatch):
    s = agent.settings
    s.telegram_token, s.telegram_chat_id, s.gemini_api_key = "t", "7", ""
    bot = TelegramBot(agent, s)
    sent = []

    async def fake_send(chat_id, text, markdown=True):
        sent.append(text)
        return True

    monkeypatch.setattr(bot, "send_text", fake_send)
    await bot.handle_update({"message": {"chat": {"id": 7}, "voice": {"file_id": "f1"}}})
    assert "GEMINI_API_KEY" in sent[0]
