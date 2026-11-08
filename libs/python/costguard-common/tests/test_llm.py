import json
import urllib.parse

import httpx
import pytest

from costguard_common import llm


def gateway(answer, status=200, seen=None):
    def handle(request):
        if seen is not None:
            seen.append(request)
        return httpx.Response(status, json=answer)

    return httpx.Client(base_url="http://gw", transport=httpx.MockTransport(handle))


def test_an_openai_style_call_goes_through_the_gateway_with_the_agent_key():
    seen = []
    model = llm.GatewayLlm(gateway({"choices": [{"message": {"content": "{}"}}]}, seen=seen), "cg_live_platform",
                           "openai", "gpt-x", goal="Извлечь счёт")
    assert model.complete("sys", "user", task_id="t/1") == "{}"
    [r] = seen
    assert r.url.path == "/v1/providers/openai/v1/chat/completions"
    assert r.headers["authorization"] == "Bearer cg_live_platform"
    assert r.headers["x-costguard-task"] == "t/1"
    assert urllib.parse.unquote(r.headers["x-costguard-task-goal"]) == "Извлечь счёт"
    assert r.headers["idempotency-key"]
    body = json.loads(r.content)
    assert body["model"] == "gpt-x" and body["messages"][0] == {"role": "system", "content": "sys"}


def test_every_call_is_a_new_operation():
    seen = []
    model = llm.GatewayLlm(gateway({"choices": [{"message": {"content": "x"}}]}, seen=seen), "k", "openai", "m")
    model.complete("s", "u")
    model.complete("s", "u")
    assert seen[0].headers["idempotency-key"] != seen[1].headers["idempotency-key"]


def test_anthropic_and_yandex_shapes():
    seen = []
    a = llm.GatewayLlm(gateway({"content": [{"type": "text", "text": "A"}]}, seen=seen), "k", "anthropic", "m")
    assert a.complete("s", "u") == "A" and seen[-1].url.path == "/v1/providers/anthropic/v1/messages"
    y = llm.GatewayLlm(gateway({"result": {"alternatives": [{"message": {"text": "Y"}}]}}, seen=seen), "k",
                       "yandex", "yandexgpt", folder_id="f1")
    assert y.complete("s", "u") == "Y"
    assert json.loads(seen[-1].content)["modelUri"] == "gpt://f1/yandexgpt"


def test_a_refusal_of_the_gateway_says_why():
    model = llm.GatewayLlm(gateway({"error": {"type": "budget_exceeded"}}, status=402), "k", "openai", "m")
    with pytest.raises(llm.LlmRefused, match="402 budget_exceeded"):
        model.complete("s", "u")


def test_an_unreachable_gateway_and_an_empty_answer_are_refusals():
    def down(request):
        raise httpx.ConnectError("refused")

    model = llm.GatewayLlm(httpx.Client(base_url="http://gw", transport=httpx.MockTransport(down)), "k", "openai",
                           "m")
    with pytest.raises(llm.LlmRefused, match="did not answer"):
        model.complete("s", "u")
    with pytest.raises(llm.LlmRefused, match="empty"):
        llm.GatewayLlm(gateway({"choices": []}), "k", "openai", "m").complete("s", "u")


def test_json_of_an_answer():
    assert llm.json_of('```json\n{"a": 1}\n```') == {"a": 1}
    assert llm.json_of('Вот ответ: {"a": {"b": 2}} — готово') == {"a": {"b": 2}}
    with pytest.raises(ValueError):
        llm.json_of("нет json")
