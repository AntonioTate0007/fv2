"""Model-backed brains. Both use the same prompt-based JSON tool-calling scheme so a
tiny local model and Gemini behave identically from the agent's point of view."""

from __future__ import annotations

import json
import logging
import re
from typing import Optional

import httpx

from .base import Context, Plan

log = logging.getLogger("jarvis.brain")

SYSTEM = """You are {name}, a concise personal assistant living on the user's Android phone.
You can control the phone with TOOLS. Decide what the user wants and answer with ONE JSON object and nothing else.

Tools:
{tools}

Output format (exactly one of):
{{"tool": "<tool name>", "args": {{...}}, "say": "<optional short remark>"}}
{{"reply": "<your answer in one or two sentences>"}}

Rules:
- Use a tool whenever the request maps to one. Fill args from the message.
- For questions you can answer yourself, use "reply".
- Never invent tool names. Never output markdown or explanations, only JSON."""


def build_system(ctx: Context) -> str:
    return SYSTEM.format(name=ctx.name, tools=ctx.tools_spec)


def build_messages(text: str, ctx: Context) -> list[dict]:
    msgs: list[dict] = []
    for h in ctx.history[-6:]:
        role = "user" if h.get("role") == "user" else "assistant"
        msgs.append({"role": role, "content": h.get("text", "")})
    msgs.append({"role": "user", "content": text})
    return msgs


_JSON_RE = re.compile(r"\{.*\}", re.S)


def parse_plan(raw: str, tool_names: list[str]) -> Optional[Plan]:
    """Turn model output into a Plan. Tolerates code fences and chatter around the
    JSON. Unknown tools become a plain reply so the user still sees something."""
    if not raw:
        return None
    raw = raw.strip()
    raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw, flags=re.S)
    m = _JSON_RE.search(raw)
    if not m:
        return Plan(reply=raw[:1000])
    try:
        obj = json.loads(m.group(0))
    except json.JSONDecodeError:
        # try to salvage: model sometimes emits several objects; take the first
        try:
            obj = json.JSONDecoder().raw_decode(m.group(0))[0]
        except Exception:
            return Plan(reply=raw[:1000])
    if not isinstance(obj, dict):
        return Plan(reply=raw[:1000])
    tool = obj.get("tool") or obj.get("name") or obj.get("function")
    if tool:
        tool = str(tool).strip()
        args = obj.get("args") or obj.get("arguments") or obj.get("parameters") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {"text": args}
        if not isinstance(args, dict):
            args = {}
        if tool not in tool_names:
            return Plan(reply=obj.get("say") or obj.get("reply")
                        or f"I wanted to use '{tool}' but I don't have that tool.")
        return Plan(tool=tool, args=args, reply=(obj.get("say") or None))
    reply = obj.get("reply") or obj.get("say") or obj.get("answer") or obj.get("content")
    return Plan(reply=str(reply)) if reply else Plan(reply=raw[:1000])


class LocalLLMBrain:
    label = "local"

    def __init__(self, url: str, model: str = "local", timeout: float = 60.0):
        self.url = url.rstrip("/")
        self.model = model
        self.timeout = timeout

    async def healthy(self) -> tuple[bool, str]:
        try:
            async with httpx.AsyncClient(timeout=5.0) as c:
                r = await c.get(f"{self.url}/models")
                if r.status_code == 200:
                    ids = [m.get("id") for m in (r.json().get("data") or [])]
                    return True, f"{self.url} ({', '.join(ids[:3]) or 'ready'})"
                return False, f"{self.url} → HTTP {r.status_code}"
        except Exception as e:
            return False, f"{self.url} unreachable ({e.__class__.__name__})"

    async def plan(self, text: str, ctx: Context) -> Optional[Plan]:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": build_system(ctx)},
                         *build_messages(text, ctx)],
            "temperature": 0.1,
            "max_tokens": 200,
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as c:
                r = await c.post(f"{self.url}/chat/completions", json=body)
                r.raise_for_status()
                raw = r.json()["choices"][0]["message"]["content"]
        except Exception as e:
            log.warning("local model failed: %s", e)
            return None
        plan = parse_plan(raw, ctx.tool_names)
        if plan:
            plan.source = self.label
        return plan


class GeminiBrain:
    label = "gemini"
    API = "https://generativelanguage.googleapis.com/v1beta/models"

    def __init__(self, api_key: str, model: str = "gemini-2.0-flash"):
        self.api_key = api_key
        self.model = model

    async def healthy(self) -> tuple[bool, str]:
        if not self.api_key:
            return False, "GEMINI_API_KEY not set"
        return True, self.model

    async def plan(self, text: str, ctx: Context) -> Optional[Plan]:
        if not self.api_key:
            return None
        contents = []
        for m in build_messages(text, ctx):
            contents.append({"role": "user" if m["role"] == "user" else "model",
                             "parts": [{"text": m["content"]}]})
        body = {
            "systemInstruction": {"parts": [{"text": build_system(ctx)}]},
            "contents": contents,
            "generationConfig": {"temperature": 0.2, "maxOutputTokens": 300,
                                 "responseMimeType": "application/json"},
        }
        try:
            async with httpx.AsyncClient(timeout=40.0) as c:
                r = await c.post(f"{self.API}/{self.model}:generateContent",
                                 params={"key": self.api_key}, json=body)
                r.raise_for_status()
                data = r.json()
                raw = data["candidates"][0]["content"]["parts"][0]["text"]
        except Exception as e:
            log.warning("gemini failed: %s", e)
            return None
        plan = parse_plan(raw, ctx.tool_names)
        if plan:
            plan.source = self.label
        return plan
