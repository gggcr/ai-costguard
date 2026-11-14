"""Shows the NEAR part of CostGuard end to end, without Docker or Kafka.

  verify  (no key needed)   checks a receipt of the demo batch against the root anchored in the deployed contract,
                            then checks a forged receipt and gets false.
  anchor  (needs a key)     builds a batch of demo receipts, anchors its Merkle root with the contract's `anchor`
                            method, and writes the batch to tools/near/demo-batch.json for `verify`.
  confirm (needs a key)     records that a person approved a large agent payment (`confirm_operation`).

The receipts are built the way the journal builds them (a hash chain of entries); the Merkle tree and the RPC client
are the notary's own (apps/python/notary). Run from the repository root:

  uv run python tools/near/demo.py verify
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import uuid
from pathlib import Path

import httpx

from costguard_notary import merkle
from costguard_notary.near import Rpc, Signer

HERE = Path(__file__).resolve().parent
BATCH_FILE = HERE / "demo-batch.json"
DEFAULT_RPC = "https://rpc.testnet.near.org"
EXPLORER = "https://testnet.nearblocks.io/txns/"


def contract_id() -> str:
    if os.environ.get("NEAR_CONTRACT_ID"):
        return os.environ["NEAR_CONTRACT_ID"]
    if BATCH_FILE.exists():
        return json.loads(BATCH_FILE.read_text(encoding="utf-8"))["contract_id"]
    sys.exit("Set NEAR_CONTRACT_ID")


def view(method: str, args: dict) -> object:
    """A read-only contract call: free, no account, no key."""
    import base64

    body = {"jsonrpc": "2.0", "id": 1, "method": "query",
            "params": {"request_type": "call_function", "finality": "final", "account_id": contract_id(),
                       "method_name": method,
                       "args_base64": base64.b64encode(json.dumps(args).encode()).decode()}}
    response = httpx.post(os.environ.get("NEAR_RPC_URL", DEFAULT_RPC), json=body, timeout=30)
    response.raise_for_status()
    data = response.json()
    if "error" in data:
        sys.exit(f"NEAR: {data['error']}")
    return json.loads(bytes(data["result"]["result"]).decode())


def demo_receipts(count: int) -> list[dict]:
    """Agent payments as the gateway journals them; each receipt hash chains to the previous one."""
    previous = "0" * 64
    out = []
    for i in range(count):
        entry = {"agent": "procurement-bot", "vendor": "api.openai.com", "amount_rub": 120 + 37 * i,
                 "decision": "allowed", "seq": i + 1}
        text = previous + "\n" + json.dumps(entry, sort_keys=True, ensure_ascii=False)
        receipt_hash = hashlib.sha256(text.encode()).hexdigest()
        out.append({"entry": entry, "hash": receipt_hash})
        previous = receipt_hash
    return out


def signer() -> Signer:
    account, key = os.environ.get("NEAR_ACCOUNT_ID"), os.environ.get("NEAR_PRIVATE_KEY")
    if not account or not key:
        sys.exit("Set NEAR_ACCOUNT_ID and NEAR_PRIVATE_KEY (the notary account allowed by the contract)")
    return Signer.from_secret(account, key)


def rpc() -> Rpc:
    return Rpc(httpx.Client(base_url=os.environ.get("NEAR_RPC_URL", DEFAULT_RPC), timeout=60))


def cmd_anchor(args: argparse.Namespace) -> None:
    receipts = demo_receipts(args.count)
    leaves = [r["hash"] for r in receipts]
    root = merkle.root(leaves)
    batch_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "costguard:demo:" + root))
    now = int(time.time() * 1000)
    tx = rpc().call(signer(), contract_id(), "anchor",
                    {"batch_id": batch_id, "merkle_root": root, "receipt_count": len(leaves),
                     "period_start_ms": now, "period_end_ms": now})
    BATCH_FILE.write_text(json.dumps({"contract_id": contract_id(), "batch_id": batch_id, "merkle_root": root,
                                      "anchor_tx": tx, "receipts": receipts}, indent=2, ensure_ascii=False) + "\n",
                          encoding="utf-8")
    print(f"Anchored {len(leaves)} receipts, root {root}")
    print(f"Transaction: {EXPLORER}{tx}")


def cmd_verify(args: argparse.Namespace) -> None:
    batch = json.loads(BATCH_FILE.read_text(encoding="utf-8"))
    leaves = [r["hash"] for r in batch["receipts"]]
    i = args.receipt
    receipt = batch["receipts"][i]
    proof = merkle.path(leaves, i)
    print(f"Contract {contract_id()}, batch {batch['batch_id']}")
    print(f"Anchored on chain: {view('get_anchor', {'batch_id': batch['batch_id']})}")
    print(f"Anchor transaction: {EXPLORER}{batch['anchor_tx']}")
    print(f"\nReceipt #{i + 1}: {json.dumps(receipt['entry'], ensure_ascii=False)}")
    ok = view("verify", {"batch_id": batch["batch_id"], "leaf": receipt["hash"], "proof": proof})
    print(f"  contract verify -> {ok}   (the receipt is in the anchored batch)")
    forged = dict(receipt["entry"], amount_rub=1)
    previous = batch["receipts"][i - 1]["hash"] if i else "0" * 64
    forged_text = previous + "\n" + json.dumps(forged, sort_keys=True, ensure_ascii=False)
    forged_hash = hashlib.sha256(forged_text.encode()).hexdigest()
    bad = view("verify", {"batch_id": batch["batch_id"], "leaf": forged_hash, "proof": proof})
    print("\nSame receipt with the amount changed to 1 RUB after the fact:")
    print(f"  contract verify -> {bad}  (tampering is detected without trusting CostGuard)")
    if ok is not True or bad is not False:
        sys.exit(1)


def cmd_confirm(args: argparse.Namespace) -> None:
    operation = hashlib.sha256(f"payment:{args.amount}:{args.vendor}:{time.time_ns()}".encode()).hexdigest()
    approver = hashlib.sha256(f"salt:{args.approver}".encode()).hexdigest()
    tx = rpc().call(signer(), contract_id(), "confirm_operation",
                    {"operation_hash": operation, "approver_hash": approver})
    print(f"Confirmed operation {operation}")
    print(f"Transaction: {EXPLORER}{tx}")
    print(f"is_confirmed -> {view('is_confirmed', {'operation_hash': operation, 'approver_hash': None})}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("anchor")
    a.add_argument("--count", type=int, default=8)
    a.set_defaults(func=cmd_anchor)
    v = sub.add_parser("verify")
    v.add_argument("--receipt", type=int, default=2, help="index of the receipt to check (0-based)")
    v.set_defaults(func=cmd_verify)
    c = sub.add_parser("confirm")
    c.add_argument("--amount", default="250000")
    c.add_argument("--vendor", default="api.anthropic.com")
    c.add_argument("--approver", default="cfo")
    c.set_defaults(func=cmd_confirm)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
