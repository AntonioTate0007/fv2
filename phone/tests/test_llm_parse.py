from jarvis_phone.brain import parse_plan

TOOLS = ["battery", "torch", "remind"]


def test_plain_tool_call():
    p = parse_plan('{"tool": "torch", "args": {"on": true}}', TOOLS)
    assert p.tool == "torch" and p.args == {"on": True}


def test_code_fence_and_chatter():
    raw = 'Sure! ```json\n{"tool": "battery", "args": {}, "say": "Checking"}\n```'
    p = parse_plan(raw, TOOLS)
    assert p.tool == "battery" and p.reply == "Checking"


def test_reply_object():
    p = parse_plan('{"reply": "Peru\'s capital is Lima."}', TOOLS)
    assert p.tool is None and "Lima" in p.reply


def test_unknown_tool_degrades_to_reply():
    p = parse_plan('{"tool": "launch_rockets", "args": {}}', TOOLS)
    assert p.tool is None and "launch_rockets" in p.reply


def test_openai_style_arguments_string():
    p = parse_plan('{"name": "remind", "arguments": "{\\"when\\": \\"in 5 minutes\\", \\"text\\": \\"tea\\"}"}', TOOLS)
    assert p.tool == "remind" and p.args["text"] == "tea"


def test_non_json_text_becomes_reply():
    p = parse_plan("I cannot do that.", TOOLS)
    assert p.tool is None and p.reply == "I cannot do that."


def test_empty():
    assert parse_plan("", TOOLS) is None
