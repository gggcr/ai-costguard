"""CostGuard SDK for agents.

    from costguard import CostGuard

    cg = CostGuard("cg_live_...", base_url="https://gateway.example.ru")
    with cg.spend("Ozon", 1290, product="Картридж", task_id="ticket-42") as op:
        order = buy()                 # runs only if the gateway allowed the amount
        op.settle(order.total)        # charge what was really spent; without it the reserve is charged in full

An exception inside the block releases the reserve. A refusal raises Refused; an operation held for a person
raises ApprovalRequired, and `cg.wait_for_approval(e.approval_id)` waits for the decision; after "approved",
calling spend again with the same operation_id goes through.

For LLM calls, point the provider's own client at `cg.provider_url("openai")` with the agent key: the gateway
meters tokens itself.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from decimal import Decimal

import httpx

__all__ = ["CostGuard", "Operation", "CostGuardError", "Refused", "ApprovalRequired"]

PROVIDERS = ("openai", "anthropic", "gigachat", "yandex")


class CostGuardError(Exception):
    def __init__(self, status: int, type: str, message: str = "") -> None:
        super().__init__(f"{status} {type}: {message or type}")
        self.status, self.type, self.message = status, type, message


class Refused(CostGuardError):
    """The gateway said no: budget_exceeded, policy_denied, agent_suspended, rate_limited, duplicate_operation…"""


class ApprovalRequired(CostGuardError):
    def __init__(self, status: int, message: str, approval_id: str) -> None:
        super().__init__(status, "approval_required", message)
        self.approval_id = approval_id


def _amount(value) -> float:
    return float(Decimal(str(value)))


class CostGuard:
    def __init__(self, api_key: str, base_url: str = "http://localhost:8081", timeout: float = 30,
                 client: httpx.Client | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self._http = client or httpx.Client(base_url=self.base_url, timeout=timeout)
        self._headers = {"Authorization": f"Bearer {api_key}"}

    def _post(self, path: str, body: dict, headers: dict | None = None) -> dict:
        return self._read(self._http.post(path, json=body, headers={**self._headers, **(headers or {})}))

    @staticmethod
    def _read(response: httpx.Response) -> dict:
        try:
            body = response.json()
        except ValueError:
            body = {}
        if response.status_code < 400:
            return body
        error = body.get("error", {}) if isinstance(body, dict) else {}
        kind = error.get("type") or f"http_{response.status_code}"
        if kind == "approval_required":
            raise ApprovalRequired(response.status_code, error.get("message", ""), error.get("approvalId", ""))
        raise Refused(response.status_code, kind, error.get("message", ""))

    def authorize(self, vendor: str, amount, product: str | None = None, task_id: str | None = None,
                  kind: str = "paid_api", operation_id: str | None = None) -> Operation:
        """Runs the check ladder and reserves the amount. Repeat with the same operation_id after an approval."""
        operation_id = operation_id or str(uuid.uuid4())
        body = self._post("/v1/spend/authorize",
                          {"vendor": vendor, "amount": _amount(amount), "product": product, "taskId": task_id,
                           "kind": kind},
                          {"Idempotency-Key": operation_id})
        return Operation(self, body.get("authorizationId", operation_id), Decimal(str(body.get("reserved", amount))))

    def spend(self, vendor: str, amount, **kw) -> Operation:
        return self.authorize(vendor, amount, **kw)

    def settle(self, authorization_id: str, amount) -> dict:
        return self._post("/v1/spend/settle", {"authorizationId": authorization_id, "amount": _amount(amount)})

    def release(self, authorization_id: str) -> dict:
        return self._post("/v1/spend/release", {"authorizationId": authorization_id})

    def approval(self, approval_id: str) -> str:
        """pending, approved, rejected or expired."""
        return self._read(self._http.get(f"/v1/spend/approvals/{approval_id}", headers=self._headers))["status"]

    def wait_for_approval(self, approval_id: str, timeout: float = 3600, every: float = 10,
                          sleep=time.sleep) -> str:
        waited = 0.0
        while True:
            status = self.approval(approval_id)
            if status != "pending" or waited >= timeout:
                return status
            sleep(every)
            waited += every

    def provider_url(self, provider: str) -> str:
        if provider not in PROVIDERS:
            raise ValueError(f"provider must be one of {', '.join(PROVIDERS)}")
        return f"{self.base_url}/v1/providers/{provider}"


@dataclass
class Operation:
    """An authorized operation. As a context manager it settles on success and releases on an exception."""

    client: CostGuard
    authorization_id: str
    reserved: Decimal
    done: bool = False

    def settle(self, amount=None) -> None:
        self.client.settle(self.authorization_id, self.reserved if amount is None else amount)
        self.done = True

    def release(self) -> None:
        self.client.release(self.authorization_id)
        self.done = True

    def __enter__(self) -> Operation:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.done:
            return
        if exc_type is None:
            self.settle()
        else:
            self.release()
