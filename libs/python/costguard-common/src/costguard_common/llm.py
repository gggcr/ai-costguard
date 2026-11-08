"""Language models for the platform's own services, always through the CostGuard gateway.

A service holds a virtual key of an agent of CostGuard's own tenant, never a provider key: the gateway checks the
call against that agent's mandate and budget, counts the tokens and swaps the key (ADR 0013). The call goes to
/v1/providers/<provider>/... in the provider's own API; openai, gigachat (OpenAI-style), anthropic and yandex are
spoken. Every call gets its own operation id, a task id and a goal, so the gateway's AI assessor knows what the
call is for.
"""

from __future__ import annotations

import json
import urllib.parse
import uuid
from dataclasses import dataclass
from typing import Protocol

import httpx


class LlmRefused(Exception):
    """The gateway or the provider said no (budget, mandate, approval) or did not answer; message says why."""


class Llm(Protocol):
    def complete(self, system: str, user: str, task_id: str | None = None) -> str: ...


@dataclass
class GatewayLlm:
    client: httpx.Client          # base_url: the gateway, such as http://gateway:8081
    api_key: str                  # virtual key of the platform agent
    provider: str
    model: str
    goal: str = ""
    max_tokens: int = 2000
    folder_id: str = ""           # yandex only

    def _call(self, path: str, body: dict, task_id: str | None) -> dict:
        headers = {"Authorization": f"Bearer {self.api_key}", "Idempotency-Key": str(uuid.uuid4())}
        if task_id:
            headers["X-CostGuard-Task"] = task_id
        if self.goal:
            headers["X-CostGuard-Task-Goal"] = urllib.parse.quote(self.goal, safe="")
        try:
            response = self.client.post(f"/v1/providers/{self.provider}{path}", json=body, headers=headers)
        except httpx.HTTPError as e:
            raise LlmRefused(f"The gateway did not answer: {e}") from e
        if response.status_code >= 400:
            try:
                error = response.json().get("error", {})
                reason = error.get("type") or error.get("message") or response.text[:200]
            except ValueError:
                reason = response.text[:200]
            raise LlmRefused(f"The gateway refused the call ({response.status_code} {reason})")
        try:
            return response.json()
        except ValueError as e:
            raise LlmRefused("The provider answered with something that is not JSON") from e

    def complete(self, system: str, user: str, task_id: str | None = None) -> str:
        if self.provider == "anthropic":
            body = {"model": self.model, "max_tokens": self.max_tokens, "temperature": 0, "system": system,
                    "messages": [{"role": "user", "content": user}]}
            answer = self._call("/v1/messages", body, task_id)
            parts = [c.get("text", "") for c in answer.get("content", []) if c.get("type") == "text"]
            text = "".join(parts)
        elif self.provider == "yandex":
            body = {"modelUri": f"gpt://{self.folder_id}/{self.model}",
                    "completionOptions": {"temperature": 0, "maxTokens": str(self.max_tokens)},
                    "messages": [{"role": "system", "text": system}, {"role": "user", "text": user}]}
            answer = self._call("/foundationModels/v1/completion", body, task_id)
            alternatives = answer.get("result", {}).get("alternatives", [])
            text = alternatives[0].get("message", {}).get("text", "") if alternatives else ""
        else:
            body = {"model": self.model, "max_tokens": self.max_tokens, "temperature": 0,
                    "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
            answer = self._call("/v1/chat/completions", body, task_id)
            choices = answer.get("choices", [])
            text = choices[0].get("message", {}).get("content", "") if choices else ""
        if not text:
            raise LlmRefused("The model gave an empty answer")
        return text


def json_of(answer: str) -> dict:
    """The JSON object in a model's answer, with or without a ```json fence around it."""
    text = answer.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else ""
        text = text.rsplit("```", 1)[0]
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise ValueError("No JSON object in the answer")
    value = json.loads(text[start:end + 1])
    if not isinstance(value, dict):
        raise ValueError("The answer is not a JSON object")
    return value
