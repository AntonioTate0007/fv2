import json

import httpx

from jarvis_phone.brain import AutoBrain, GeminiBrain, LocalLLMBrain, RulesBrain
from jarvis_phone.brain.base import Context

CTX = Context(history=[{"role": "user", "text": "hi"}, {"role": "assistant", "text": "hello"}],
              tools_spec="- battery(): battery", tool_names=["battery", "torch"], name="Jarvis")


def mock_http(monkeypatch, handler):
    """Route every httpx.AsyncClient through `handler(request) -> Response`."""
    real = httpx.AsyncClient

    def factory(*a, **kw):
        kw["transport"] = httpx.MockTransport(handler)
        return real(*a, **kw)

    monkeypatch.setattr(httpx, "AsyncClient", factory)


async def test_local_llm_sends_openai_shape_and_parses(monkeypatch):
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["url"] = str(req.url)
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"choices": [{"message": {
            "content": '{"tool": "torch", "args": {"on": "false"}, "say": "Lights out."}'}}]})

    mock_http(monkeypatch, handler)
    plan = await LocalLLMBrain("http://127.0.0.1:8080/v1", "needle").plan("kill the torch", CTX)
    assert seen["url"].endswith("/v1/chat/completions")
    msgs = seen["body"]["messages"]
    assert msgs[0]["role"] == "system" and "battery" in msgs[0]["content"]
    assert msgs[-1] == {"role": "user", "content": "kill the torch"}
    assert seen["body"]["model"] == "needle"
    assert plan.tool == "torch" and plan.args == {"on": "false"}
    assert plan.reply == "Lights out." and plan.source == "local"


async def test_local_llm_down_returns_none(monkeypatch):
    def handler(req):
        raise httpx.ConnectError("refused")
    mock_http(monkeypatch, handler)
    assert await LocalLLMBrain("http://127.0.0.1:8080/v1").plan("x", CTX) is None


async def test_gemini_shape(monkeypatch):
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["url"] = str(req.url)
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [
            {"text": '{"reply": "Lima."}'}]}}]})

    mock_http(monkeypatch, handler)
    plan = await GeminiBrain("k", "gemini-2.0-flash").plan("capital of peru?", CTX)
    assert "gemini-2.0-flash:generateContent" in seen["url"] and "key=k" in seen["url"]
    assert seen["body"]["contents"][-1]["role"] == "user"
    assert seen["body"]["generationConfig"]["responseMimeType"] == "application/json"
    assert plan.tool is None and plan.reply == "Lima." and plan.source == "gemini"


async def test_gemini_without_key_is_skipped():
    assert await GeminiBrain("").plan("x", CTX) is None


async def test_auto_prefers_rules_then_falls_through(monkeypatch):
    calls = []

    def handler(req):
        calls.append(1)
        return httpx.Response(200, json={"choices": [{"message": {
            "content": '{"reply": "from model"}'}}]})

    mock_http(monkeypatch, handler)
    brain = AutoBrain([RulesBrain(), LocalLLMBrain("http://x/v1")])
    p = await brain.plan("battery", CTX)
    assert p.tool == "battery" and p.source == "rules" and calls == []
    p = await brain.plan("what's the capital of peru", CTX)
    assert p.reply == "from model" and p.source == "local" and calls == [1]


async def test_auto_survives_crashing_brain():
    class Boom:
        label = "boom"

        async def plan(self, t, c):
            raise RuntimeError("nope")

        async def healthy(self):
            return False, "boom"

    p = await AutoBrain([Boom(), RulesBrain()]).plan("battery", CTX)
    assert p.tool == "battery"
