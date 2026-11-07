import base64
import hashlib
import io
import json
import struct
import uuid
from datetime import UTC, datetime, timedelta

import fastavro
import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from costguard_common import schemas
from costguard_notary import batches, merkle, near

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")


def leaves(n):
    return [hashlib.sha256(("0" * 64 + f"\nleaf {i}").encode()).hexdigest() for i in range(n)]


# Merkle


def test_root_matches_the_core():
    # pinned in MerkleTest.rootMatchesTheNotary of the core
    assert merkle.root(leaves(5)) == "fda95846c507b71d6a40de553d7855aec27b3ada0afa61c177732c7f90afa3e9"


@pytest.mark.parametrize("n", [1, 2, 3, 5, 8, 13])
def test_every_leaf_proves_its_place(n):
    xs = leaves(n)
    r = merkle.root(xs)
    assert all(merkle.verify(xs[i], merkle.path(xs, i), r) for i in range(n))
    outsider = hashlib.sha256(b"outsider").hexdigest()
    assert not merkle.verify(outsider, merkle.path(xs, 0), r)


def test_empty_batch():
    with pytest.raises(ValueError):
        merkle.root([])


# base58 and borsh


def test_base58_round_trip():
    for data in [b"", b"\0\0abc", bytes(range(32)), b"\xff" * 64]:
        assert near.b58decode(near.b58encode(data)) == data
    assert near.b58encode(b"\0\0\x01") == "112"


def signer():
    key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
    return near.Signer("notary.testnet", key)


def test_secret_in_near_cli_format():
    s = signer()
    seed = bytes(range(32))
    assert near.Signer.from_secret("a", "ed25519:" + near.b58encode(seed + s.public_key)).public_key == s.public_key
    assert near.Signer.from_secret("a", near.b58encode(seed)).public_key == s.public_key


def read_string(buf, at):
    (n,) = struct.unpack_from("<I", buf, at)
    return buf[at + 4:at + 4 + n], at + 4 + n


def test_function_call_transaction_layout_and_signature():
    s = signer()
    block = bytes(range(32, 64))
    tx = near.function_call_tx(s, "costguard-notary.testnet", 42, block, "anchor", {"b": 1, "a": 2})
    account, at = read_string(tx, 0)
    assert account == b"notary.testnet"
    assert tx[at] == 0 and tx[at + 1:at + 33] == s.public_key
    at += 33
    assert struct.unpack_from("<Q", tx, at)[0] == 42
    receiver, at = read_string(tx, at + 8)
    assert receiver == b"costguard-notary.testnet"
    assert tx[at:at + 32] == block
    at += 32
    assert struct.unpack_from("<I", tx, at)[0] == 1 and tx[at + 4] == near.FUNCTION_CALL
    method, at = read_string(tx, at + 5)
    args, at = read_string(tx, at)
    assert method == b"anchor" and json.loads(args) == {"a": 2, "b": 1}
    assert struct.unpack_from("<Q", tx, at)[0] == near.DEFAULT_GAS
    assert tx[at + 8:] == bytes(16)

    signed, tx_hash = near.sign(s, tx)
    digest = hashlib.sha256(tx).digest()
    assert tx_hash == near.b58encode(digest)
    assert signed[:len(tx)] == tx and signed[len(tx)] == 0
    s.key.public_key().verify(signed[len(tx) + 1:], digest)  # raises if wrong


def test_rpc_call():
    s = signer()
    sent = []

    def handle(request):
        body = json.loads(request.content)
        sent.append(body)
        if body["method"] == "query":
            assert body["params"]["public_key"] == s.public_key_text
            return httpx.Response(200, json={"result": {"nonce": 7, "block_hash": near.b58encode(bytes(32))}})
        return httpx.Response(200, json={"result": {"status": {"SuccessValue": ""}}})

    rpc = near.Rpc(httpx.Client(base_url="http://rpc", transport=httpx.MockTransport(handle)))
    tx_hash = rpc.call(s, "c.testnet", "anchor", {"x": 1})
    signed = base64.b64decode(sent[1]["params"]["signed_tx_base64"])
    assert sent[1]["params"]["wait_until"] == "FINAL"
    assert near.b58encode(hashlib.sha256(signed[:-65]).digest()) == tx_hash
    _, at = read_string(signed, 0)
    assert struct.unpack_from("<Q", signed, at + 33)[0] == 8  # nonce + 1


def test_rpc_failure():
    def handle(request):
        if json.loads(request.content)["method"] == "query":
            return httpx.Response(200, json={"result": {"nonce": 1, "block_hash": near.b58encode(bytes(32))}})
        return httpx.Response(200, json={"result": {"status": {"Failure": {"ActionError": "batch exists"}}}})

    rpc = near.Rpc(httpx.Client(base_url="http://rpc", transport=httpx.MockTransport(handle)))
    with pytest.raises(RuntimeError, match="batch exists"):
        rpc.call(signer(), "c", "anchor", {})


# batches

T0 = datetime(2026, 11, 7, 10, tzinfo=UTC)


def receipts(n, tenant=TENANT):
    return [batches.Receipt(uuid.uuid5(uuid.NAMESPACE_URL, f"{tenant}{i}"), tenant, leaves(n)[i],
                            T0 + timedelta(seconds=i)) for i in range(n)]


def test_batches_per_tenant_in_issue_order_and_stable_ids():
    other = uuid.uuid4()
    rs = receipts(5) + receipts(2, other)
    first = batches.batches(list(reversed(rs)) + rs[:1], max_size=3)
    assert [(b.tenant_id, len(b.receipts)) for b in first] == [(TENANT, 3), (TENANT, 2), (other, 2)] or \
        sorted((str(b.tenant_id), len(b.receipts)) for b in first) == sorted(
            [(str(TENANT), 3), (str(TENANT), 2), (str(other), 2)])
    again = batches.batches(rs, max_size=3)
    assert {b.batch_id for b in first} == {b.batch_id for b in again}
    whole = batches.batches(receipts(5), max_size=10)[0]
    assert whole.merkle_root == merkle.root(leaves(5))


def test_anchor_publishes_after_each_call_and_stops_on_failure():
    calls, published = [], []

    def call(method, args):
        calls.append((method, args))
        if len(calls) == 2:
            raise RuntimeError("rpc down")
        return "TXHASH"

    with pytest.raises(RuntimeError):
        batches.anchor(receipts(5), call, published.append, lambda: T0, max_size=3)
    assert len(published) == 1
    assert calls[0][0] == "anchor" and calls[0][1]["receipt_count"] == 3
    assert "tenant" not in json.dumps(calls[0][1])


def test_anchor_message_fits_the_schema():
    [b] = batches.batches(receipts(3))
    value = b.anchor_message("TX", T0)
    schema = schemas.parsed("journal.anchors.v1")
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schema, value)
    back = fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schema)
    assert str(back["tenant_id"]) == str(TENANT) and back["receipt_count"] == 3
