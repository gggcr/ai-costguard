"""Groups receipts into batches per tenant and anchors each batch's Merkle root in the NEAR contract.

The batch id is derived from the tenant and the receipt ids, so a batch rebuilt after a crash has the same id;
the contract treats the same batch with the same root as a no-op (contracts/notary), and the core's
Receipts.anchored is safe to repeat. The contract stores no tenant id: a root reveals nothing, and the tenant
stays off the public chain.
"""

from __future__ import annotations

import uuid
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from costguard_notary import merkle

ANCHOR_METHOD = "anchor"


@dataclass(frozen=True)
class Receipt:
    receipt_id: uuid.UUID
    tenant_id: uuid.UUID
    hash: str
    issued_at: datetime


@dataclass(frozen=True)
class Batch:
    batch_id: uuid.UUID
    tenant_id: uuid.UUID
    receipts: list[Receipt]
    merkle_root: str

    @property
    def period_start(self) -> datetime:
        return self.receipts[0].issued_at

    @property
    def period_end(self) -> datetime:
        return self.receipts[-1].issued_at

    def contract_args(self) -> dict:
        return {"batch_id": str(self.batch_id), "merkle_root": self.merkle_root,
                "receipt_count": len(self.receipts),
                "period_start_ms": int(self.period_start.timestamp() * 1000),
                "period_end_ms": int(self.period_end.timestamp() * 1000)}

    def anchor_message(self, tx_hash: str, at: datetime) -> dict:
        return {"batch_id": str(self.batch_id), "tenant_id": str(self.tenant_id), "merkle_root": self.merkle_root,
                "receipt_count": len(self.receipts), "receipt_ids": [str(r.receipt_id) for r in self.receipts],
                "period_start": self.period_start, "period_end": self.period_end, "near_tx_hash": tx_hash,
                "anchored_at": at}


def batches(receipts: list[Receipt], max_size: int = 1000) -> list[Batch]:
    """Receipts in the order they were issued, per tenant, at most max_size per batch; a receipt seen twice counts
    once."""
    by_tenant: dict[uuid.UUID, dict[uuid.UUID, Receipt]] = defaultdict(dict)
    for r in receipts:
        by_tenant[r.tenant_id].setdefault(r.receipt_id, r)
    out = []
    for tenant, unique in by_tenant.items():
        ordered = sorted(unique.values(), key=lambda r: (r.issued_at, str(r.receipt_id)))
        for i in range(0, len(ordered), max_size):
            part = ordered[i:i + max_size]
            batch_id = uuid.uuid5(uuid.NAMESPACE_URL, f"costguard:anchor:{tenant}:"
                                  + ",".join(str(r.receipt_id) for r in part))
            out.append(Batch(batch_id, tenant, part, merkle.root([r.hash for r in part])))
    return out


def anchor(pending: list[Receipt], call: Callable[[str, dict], str], publish: Callable[[dict], None],
           now: Callable[[], datetime], max_size: int = 1000) -> int:
    """Anchors every batch and publishes it. Raises on the first failure, so the caller keeps its offsets."""
    done = 0
    for b in batches(pending, max_size):
        tx = call(ANCHOR_METHOD, b.contract_args())
        publish(b.anchor_message(tx, now()))
        done += 1
    return done
