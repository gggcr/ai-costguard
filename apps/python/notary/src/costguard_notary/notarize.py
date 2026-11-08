"""notary.requests.v1 in, one contract call per request, notary.confirmations.v1 out.

A MANDATE request registers the hash of a published mandate version (register_mandate); an OPERATION request
confirms a large operation a person approved (confirm_operation). The core computes the hashes, so only hashes
reach the contract, never the tenant (docs/adr/0009-notary-mandates-confirmations.md).

The contract treats the same request again as a no-op, so a request replayed after a crash costs gas but changes
nothing. A request the contract refuses, or one that is malformed, would fail the same way every time: it is
logged and skipped, so it does not block the requests behind it.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Callable
from datetime import datetime

from costguard_notary import near

log = logging.getLogger("costguard.notary.notarize")

REGISTER_MANDATE = "register_mandate"
CONFIRM_OPERATION = "confirm_operation"
_HASH = re.compile(r"[0-9a-f]{64}")


def _hash(value) -> str:
    if not isinstance(value, str) or not _HASH.fullmatch(value):
        raise ValueError(f"not a sha256 hex: {value!r}")
    return value


def contract_call(request: dict) -> tuple[str, dict]:
    """The contract method and its arguments. Raises ValueError for a request the contract would refuse."""
    kind = request["kind"]
    if kind == "MANDATE":
        version = request.get("mandate_version")
        if not isinstance(version, int) or version < 1:
            raise ValueError(f"mandate version {version!r}")
        return REGISTER_MANDATE, {"mandate_id": str(request["subject_id"]), "version": version,
                                  "mandate_hash": _hash(request["subject_hash"])}
    if kind == "OPERATION":
        return CONFIRM_OPERATION, {"operation_hash": _hash(request["subject_hash"]),
                                   "approver_hash": _hash(request.get("approver_hash"))}
    raise ValueError(f"kind {kind!r}")


def confirmation(request: dict, tx_hash: str, at: datetime) -> dict:
    return {"request_id": str(request["request_id"]), "tenant_id": str(request["tenant_id"]),
            "kind": request["kind"], "subject_id": str(request["subject_id"]),
            "mandate_version": request.get("mandate_version"), "subject_hash": request["subject_hash"],
            "near_tx_hash": tx_hash, "confirmed_at": at}


def handle(request: dict, call: Callable[[str, dict], str], publish: Callable[[dict], None],
           now: Callable[[], datetime]) -> bool:
    """Writes one request and publishes its confirmation; False when the request is skipped. A network or RPC
    failure raises, so the caller keeps its offset and the request is tried again."""
    try:
        method, args = contract_call(request)
    except (KeyError, ValueError) as e:
        log.error("Request %s skipped, it is malformed: %s", request.get("request_id"), e)
        return False
    try:
        tx_hash = call(method, args)
    except near.ContractRefused as e:
        log.error("Request %s refused by the contract: %s", request["request_id"], e)
        return False
    publish(confirmation(request, tx_hash, now()))
    return True
