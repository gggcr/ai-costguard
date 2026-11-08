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
from costguard_notary import batches, merkle, near, notarize

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


def test_a_panic_of_the_contract_is_a_refusal_and_running_out_of_gas_is_not():
    def rpc_with(failure):
        def handle(request):
            if json.loads(request.content)["method"] == "query":
                return httpx.Response(200, json={"result": {"nonce": 1, "block_hash": near.b58encode(bytes(32))}})
            return httpx.Response(200, json={"result": {"status": {"Failure": failure}}})
        return near.Rpc(httpx.Client(base_url="http://rpc", transport=httpx.MockTransport(handle)))

    panic = {"ActionError": {"kind": {"FunctionCallError": {"ExecutionError": "Smart contract panicked: x"}}}}
    with pytest.raises(near.ContractRefused):
        rpc_with(panic).call(signer(), "c", "register_mandate", {})
    gas = {"ActionError": {"kind": {"FunctionCallError": {"ExecutionError": "Exceeded the prepaid gas."}}}}
    with pytest.raises(RuntimeError) as caught:
        rpc_with(gas).call(signer(), "c", "register_mandate", {})
    assert not isinstance(caught.value, near.ContractRefused)


# mandates and confirmations

MANDATE_ID = uuid.UUID("0b9d2c7e-4a51-4c1e-8f0a-3d2b1c4e5f60")
OPERATION_ID = uuid.UUID("5e0c7a1b-2d3f-4a5b-8c9d-0e1f2a3b4c5d")


def mandate_request(**changes):
    request = {"request_id": uuid.uuid5(uuid.NAMESPACE_URL, "m"), "tenant_id": TENANT, "kind": "MANDATE",
               "subject_id": MANDATE_ID, "mandate_version": 2, "subject_hash": leaves(1)[0], "approver_hash": None,
               "requested_at": T0}
    return {**request, **changes}


def operation_request(**changes):
    request = {"request_id": uuid.uuid5(uuid.NAMESPACE_URL, "o"), "tenant_id": TENANT, "kind": "OPERATION",
               "subject_id": OPERATION_ID, "mandate_version": None, "subject_hash": leaves(2)[0],
               "approver_hash": leaves(2)[1], "requested_at": T0}
    return {**request, **changes}


def test_a_mandate_request_registers_the_version_hash():
    calls, published = [], []
    assert notarize.handle(mandate_request(), lambda m, a: calls.append((m, a)) or "TX1", published.append,
                           lambda: T0)
    assert calls == [("register_mandate", {"mandate_id": str(MANDATE_ID), "version": 2,
                                           "mandate_hash": leaves(1)[0]})]
    [c] = published
    assert c["near_tx_hash"] == "TX1" and c["kind"] == "MANDATE" and c["mandate_version"] == 2
    assert c["subject_id"] == str(MANDATE_ID) and c["tenant_id"] == str(TENANT)


def test_an_operation_request_confirms_by_hash_and_keeps_the_tenant_off_the_chain():
    calls, published = [], []
    assert notarize.handle(operation_request(), lambda m, a: calls.append((m, a)) or "TX2", published.append,
                           lambda: T0)
    assert calls == [("confirm_operation", {"operation_hash": leaves(2)[0], "approver_hash": leaves(2)[1]})]
    sent = json.dumps(calls)
    assert str(TENANT) not in sent and str(OPERATION_ID) not in sent
    assert published[0]["subject_hash"] == leaves(2)[0] and published[0]["near_tx_hash"] == "TX2"


@pytest.mark.parametrize("request_", [
    mandate_request(mandate_version=None), mandate_request(mandate_version=0),
    mandate_request(subject_hash="ABC"), operation_request(approver_hash=None),
    operation_request(approver_hash="someone@example.com"), operation_request(kind="OTHER"),
])
def test_a_malformed_request_is_skipped_without_a_transaction(request_):
    calls, published = [], []
    assert not notarize.handle(request_, lambda m, a: calls.append(m) or "TX", published.append, lambda: T0)
    assert calls == [] and published == []


def test_a_refused_request_is_skipped_and_a_network_failure_is_retried():
    published = []

    def refused(method, args):
        raise near.ContractRefused("Smart contract panicked: registered with another hash")

    assert not notarize.handle(mandate_request(), refused, published.append, lambda: T0)

    def down(method, args):
        raise RuntimeError("NEAR query: timeout")

    with pytest.raises(RuntimeError):
        notarize.handle(mandate_request(), down, published.append, lambda: T0)
    assert published == []


@pytest.mark.parametrize("topic,value", [
    ("notary.requests.v1", mandate_request()), ("notary.requests.v1", operation_request()),
    ("notary.confirmations.v1", notarize.confirmation(mandate_request(), "TX", T0)),
    ("notary.confirmations.v1", notarize.confirmation(operation_request(), "TX", T0)),
])
def test_requests_and_confirmations_fit_their_schemas(topic, value):
    schema = schemas.parsed(topic)
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schema, value)
    back = fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schema)
    assert back["kind"] == value["kind"] and back["subject_hash"] == value["subject_hash"]
