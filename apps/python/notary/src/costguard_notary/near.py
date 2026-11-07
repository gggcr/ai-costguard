"""Just enough of NEAR to call one contract method: borsh-encoded transactions, Ed25519 signatures, JSON-RPC.

A signed transaction is borsh(SignedTransaction { transaction, signature }), where the signature is Ed25519 over
sha256(borsh(transaction)) and the transaction hash is base58 of that sha256. Only the FunctionCall action
(enum index 2) is written.
"""

from __future__ import annotations

import base64
import hashlib
import itertools
import json
import struct
from dataclasses import dataclass

import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
FUNCTION_CALL = 2
DEFAULT_GAS = 30 * 10**12


def b58encode(data: bytes) -> str:
    n = int.from_bytes(data, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    return "1" * (len(data) - len(data.lstrip(b"\0"))) + out


def b58decode(text: str) -> bytes:
    n = 0
    for c in text:
        n = n * 58 + _B58.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def _string(s: str) -> bytes:
    raw = s.encode()
    return struct.pack("<I", len(raw)) + raw


def _bytes(b: bytes) -> bytes:
    return struct.pack("<I", len(b)) + b


@dataclass(frozen=True)
class Signer:
    account_id: str
    key: Ed25519PrivateKey

    @staticmethod
    def from_secret(account_id: str, secret: str) -> Signer:
        """secret as near-cli stores it: ed25519:<base58 of the 64-byte seed + public key, or the 32-byte seed>."""
        raw = b58decode(secret.removeprefix("ed25519:"))
        return Signer(account_id, Ed25519PrivateKey.from_private_bytes(raw[:32]))

    @property
    def public_key(self) -> bytes:
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

        return self.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

    @property
    def public_key_text(self) -> str:
        return "ed25519:" + b58encode(self.public_key)


def function_call_tx(signer: Signer, receiver: str, nonce: int, block_hash: bytes, method: str, args: dict,
                     gas: int = DEFAULT_GAS, deposit: int = 0) -> bytes:
    action = (bytes([FUNCTION_CALL]) + _string(method)
              + _bytes(json.dumps(args, separators=(",", ":"), sort_keys=True).encode())
              + struct.pack("<Q", gas) + deposit.to_bytes(16, "little"))
    return (_string(signer.account_id) + b"\0" + signer.public_key + struct.pack("<Q", nonce) + _string(receiver)
            + block_hash + struct.pack("<I", 1) + action)


def sign(signer: Signer, tx: bytes) -> tuple[bytes, str]:
    """The signed transaction and its hash."""
    digest = hashlib.sha256(tx).digest()
    return tx + b"\0" + signer.key.sign(digest), b58encode(digest)


class Rpc:
    def __init__(self, client: httpx.Client) -> None:
        """client carries base_url: the RPC endpoint, such as https://rpc.testnet.near.org."""
        self._http = client
        self._ids = itertools.count(1)

    def _call(self, method: str, params) -> dict:
        response = self._http.post("", json={"jsonrpc": "2.0", "id": next(self._ids), "method": method,
                                             "params": params})
        response.raise_for_status()
        body = response.json()
        if "error" in body:
            raise RuntimeError(f"NEAR {method}: {body['error']}")
        return body["result"]

    def call(self, signer: Signer, contract: str, method: str, args: dict) -> str:
        """Calls the method, waits until the transaction is final, and returns its hash."""
        key = self._call("query", {"request_type": "view_access_key", "finality": "final",
                                   "account_id": signer.account_id, "public_key": signer.public_key_text})
        tx = function_call_tx(signer, contract, key["nonce"] + 1, b58decode(key["block_hash"]), method, args)
        signed, tx_hash = sign(signer, tx)
        result = self._call("send_tx", {"signed_tx_base64": base64.b64encode(signed).decode(),
                                        "wait_until": "FINAL"})
        failure = result.get("status", {}).get("Failure") if isinstance(result.get("status"), dict) else None
        if failure:
            raise RuntimeError(f"NEAR transaction {tx_hash} failed: {failure}")
        return tx_hash
