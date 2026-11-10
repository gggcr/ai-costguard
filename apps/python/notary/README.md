# costguard-notary

The NEAR notary: makes the journal and large operations provable outside CostGuard
([ADR 0006](../../../docs/adr/0006-notary-near.md)). A long-running Kafka consumer with two consumer groups.

| In | Out | What |
|---|---|---|
| `journal.receipts.v1` | `journal.anchors.v1` | receipts collect into a batch (`NOTARY_BATCH_SIZE` or `NOTARY_INTERVAL_SECONDS`); the Merkle root of each batch goes into the contract in one NEAR transaction (`batches.py`, `merkle.py`) |
| `notary.requests.v1` | `notary.confirmations.v1` | mandate hashes and confirmations of large operations, one transaction each (`notarize.py`) |

Offsets are committed only after the anchor or confirmation is delivered, so a crash builds the same batches again.
`near.py` is its own small JSON-RPC client and ed25519 signer. The contract is `contracts/notary`.

Run one replica: every copy signs with the same NEAR key, and their nonces would collide.

## Run

```sh
uv run costguard-notary                                        # from the repository root
docker build -f apps/python/notary/Dockerfile -t costguard-notary .
```

| Variable | Default | |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL` | `localhost:9092`, `http://localhost:8085` | |
| `NEAR_RPC_URL` | `https://rpc.testnet.near.org` | |
| `NEAR_ACCOUNT_ID`, `NEAR_PRIVATE_KEY` | empty | the account that signs; the key comes from the secret |
| `NEAR_CONTRACT_ID` | empty | the deployed `costguard-notary` contract |
| `NOTARY_BATCH_SIZE`, `NOTARY_INTERVAL_SECONDS` | `1000`, `600` | |
| `NOTARY_GROUP_ID`, `NOTARY_REQUESTS_GROUP_ID` | `notary`, `notary-requests` | |

## Tests

```sh
uv run pytest apps/python/notary
```
