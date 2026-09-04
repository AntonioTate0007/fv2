import pytest

from jarvis_phone.brain import RulesBrain
from jarvis_phone.brain.base import Context

CTX = Context(history=[], tools_spec="", tool_names=[])


@pytest.mark.parametrize("text,tool,args", [
    ("battery", "battery", {}),
    ("how much charge do I have", "battery", {}),
    ("turn on the flashlight", "torch", {"on": "true"}),
    ("flashlight off", "torch", {"on": "false"}),
    ("torch", "torch", {"on": "true"}),
    ("where am I", "location", {}),
    ("what wifi am I on", "wifi", {}),
    ("what time is it", "time", {}),
    ("vibrate", "vibrate", {}),
    ("buzz for 900 ms", "vibrate", {"ms": 900}),
    ("brightness 30%", "brightness", {"percent": 30}),
    ("dim the screen", "brightness", {"percent": 15}),
    ("volume 20%", "volume", {"percent": 20, "stream": "music"}),
    ("set alarm volume to 80", "volume", {"percent": 80, "stream": "alarm"}),
    ("mute the ringer", "volume", {"percent": 0, "stream": "ring"}),
    ("say hello world", "speak", {"text": "hello world"}),
    ("notify me: drink water", "notify", {"text": "drink water"}),
    ("copy this: abc123", "clipboard_set", {"text": "abc123"}),
    ("what's on my clipboard", "clipboard_get", {}),
    ("take a photo", "photo", {"camera": "back"}),
    ("selfie", "photo", {"camera": "front"}),
    ("open youtube.com", "open_url", {"url": "youtube.com"}),
    ("text +1 555 123 4567: running late", "sms_send",
     {"number": "+1 555 123 4567", "text": "running late"}),
    ("note: buy milk", "note", {"text": "buy milk"}),
    ("remember that the gate code is 4412", "note", {"text": "the gate code is 4412"}),
    ("show my notes", "notes", {}),
    ("clear my notes", "clear_notes", {}),
    ("what reminders do I have", "reminders", {}),
    ("cancel my reminders", "cancel_reminders", {}),
    ("how am I doing", "fortress_status", {}),
    ("positions", "fortress_positions", {}),
    ("run the swarm", "fortress_run", {}),
    ("run a cycle at 2500", "fortress_run", {"capital": 2500}),
])
@pytest.mark.asyncio
async def test_intents(text, tool, args):
    plan = await RulesBrain().plan(text, CTX)
    assert plan is not None, text
    assert plan.tool == tool
    for k, v in args.items():
        assert plan.args.get(k) == v, (text, plan.args)


@pytest.mark.asyncio
async def test_reminder_parsing():
    b = RulesBrain()
    p = await b.plan("remind me in 20 minutes to call mom", CTX)
    assert p.tool == "remind" and p.args["when"] == "in 20 minutes" and p.args["text"] == "call mom"
    p = await b.plan("remind me at 6:30pm to take the trash out", CTX)
    assert p.args["when"] == "at 6:30pm" and p.args["text"] == "take the trash out"
    p = await b.plan("remind me to stretch in 1 hour", CTX)
    assert p.args["when"] == "in 1 hour" and p.args["text"] == "stretch"


@pytest.mark.asyncio
async def test_unknown_returns_none():
    assert await RulesBrain().plan("what's the capital of peru", CTX) is None


@pytest.mark.asyncio
async def test_smalltalk_replies_without_tool():
    p = await RulesBrain().plan("hello", CTX)
    assert p.tool is None and p.reply
