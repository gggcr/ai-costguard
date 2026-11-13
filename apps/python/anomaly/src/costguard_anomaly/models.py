"""Per-agent models of the risk scorer: Isolation Forest (scikit-learn) and an online Half-Space Trees model
(river), stored and versioned in MLflow (ADR 0016).

A model judges an agent only after it learned from that agent's own operations: river after MIN_ONLINE of them
(it learns from every operation), Isolation Forest once MIN_TRAIN feature rows are collected, refitted every
RETRAIN new rows. Until then the agent is scored by the rules alone. Both give an anomaly in 0..1.

All agents' models form one bundle, saved as a new version of the registered model `costguard-anomaly-risk`
whenever enough changed, and loaded on start, so a restart does not forget what was learned. The bundle is a
pickle, and unpickling runs code: so it is signed with HMAC-SHA256 under ANOMALY_MODEL_KEY and a bundle whose
signature does not match is never unpickled (finding of 2026-11-13: whoever could write to MLflow could run code in
the detector). Without the key the models are neither saved nor loaded; they learn again after a restart.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import pickle
import tempfile
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

log = logging.getLogger("costguard.anomaly.models")

FEATURES = ("log_cost", "hour_sin", "hour_cos", "ops_1m", "ops_5m", "log_spend_5m", "new_vendor", "task_steps_5m",
            "log_input_tokens")
MIN_TRAIN = 200
RETRAIN = 1000
MIN_ONLINE = 200
KEEP_ROWS = 5000
REGISTERED = "costguard-anomaly-risk"


def _vector(features: dict[str, float]) -> list[float]:
    return [float(features.get(k, 0.0)) for k in FEATURES]


@dataclass
class AgentModels:
    rows: deque = field(default_factory=lambda: deque(maxlen=KEEP_ROWS))
    forest: object | None = None
    forest_rows: int = 0
    since_fit: int = 0
    online: object | None = None
    online_seen: int = 0

    def _online(self):
        if self.online is None:
            from river import anomaly, compose, preprocessing

            self.online = compose.Pipeline(preprocessing.MinMaxScaler(),
                                           anomaly.HalfSpaceTrees(n_trees=25, height=8, window_size=250, seed=7))
        return self.online

    def score(self, features: dict[str, float]) -> tuple[float, list[str]]:
        """The anomaly of one operation by the models that are trained, and why."""
        reasons, best = [], 0.0
        if self.forest is not None:
            decision = float(self.forest.decision_function([_vector(features)])[0])
            forest = max(0.0, min(1.0, -decision * 5))
            if forest > 0.3:
                reasons.append(f"Isolation Forest: операция необычна для агента ({forest:.2f})")
            best = max(best, forest)
        if self.online_seen >= MIN_ONLINE:
            online = float(self._online().score_one(features))
            online = max(0.0, (online - 0.6) / 0.4)  # Half-Space Trees sits near 0.5 on usual data
            if online > 0.3:
                reasons.append(f"Онлайн-модель river: поведение отличается от обычного ({online:.2f})")
            best = max(best, online)
        return best, reasons

    def learn(self, features: dict[str, float]) -> bool:
        """Learns one operation; True when Isolation Forest was refitted."""
        self._online().learn_one(features)
        self.online_seen += 1
        self.rows.append(_vector(features))
        self.since_fit += 1
        due = (self.forest is None and len(self.rows) >= MIN_TRAIN) or self.since_fit >= RETRAIN
        if due:
            from sklearn.ensemble import IsolationForest

            self.forest = IsolationForest(n_estimators=100, contamination="auto", random_state=7).fit(list(self.rows))
            self.forest_rows, self.since_fit = len(self.rows), 0
        return due

    @property
    def trained(self) -> bool:
        return self.forest is not None or self.online_seen >= MIN_ONLINE


class Store(Protocol):
    def load(self) -> bytes | None: ...

    def save(self, payload: bytes, metrics: dict[str, float]) -> str: ...


MAGIC = b"CGAM1"


def seal(body: bytes, key: bytes) -> bytes:
    """MAGIC, the HMAC-SHA256 of the pickle under the key, the pickle."""
    return MAGIC + hmac.new(key, body, hashlib.sha256).digest() + body


def unseal(payload: bytes, key: bytes) -> bytes:
    """The pickle of a bundle this key sealed; ValueError for anything else, which is then never unpickled."""
    head = len(MAGIC) + hashlib.sha256().digest_size
    if len(payload) < head or not payload.startswith(MAGIC):
        raise ValueError("the risk models bundle is not signed")
    body = payload[head:]
    if not hmac.compare_digest(payload[len(MAGIC):head], hmac.new(key, body, hashlib.sha256).digest()):
        raise ValueError("the signature of the risk models bundle does not match")
    return body


class Models:
    def __init__(self, store: Store | None = None, save_every: int = 5000, key: bytes | None = None) -> None:
        self.store = store
        self.key = key or None
        self.save_every = save_every
        self.agents: dict[uuid.UUID, AgentModels] = {}
        self.changes = 0
        self.version: str | None = None

    def of(self, agent: uuid.UUID) -> AgentModels:
        return self.agents.setdefault(agent, AgentModels())

    def score_and_learn(self, agent: uuid.UUID, features: dict[str, float]) -> tuple[float, list[str]]:
        models = self.of(agent)
        score, reasons = models.score(features)
        refit = models.learn(features)
        self.changes += 1 + (RETRAIN if refit else 0)
        if self.store is not None and self.changes >= self.save_every:
            self.save()
        return score, reasons

    def dump(self) -> bytes:
        return pickle.dumps({str(k): v for k, v in self.agents.items()})

    def restore(self, payload: bytes) -> None:
        self.agents = {uuid.UUID(k): v for k, v in pickle.loads(payload).items()}

    def save(self) -> str | None:
        if self.store is None:
            return None
        if self.key is None:
            log.warning("ANOMALY_MODEL_KEY is not set: the risk models are not saved")
            self.changes = 0
            return None
        metrics = {"agents": float(len(self.agents)),
                   "agents_with_forest": float(sum(1 for m in self.agents.values() if m.forest is not None)),
                   "agents_trained": float(sum(1 for m in self.agents.values() if m.trained))}
        self.version = self.store.save(seal(self.dump(), self.key), metrics)
        self.changes = 0
        log.info("Risk models saved as version %s of %s", self.version, REGISTERED)
        return self.version

    def load(self) -> bool:
        if self.store is not None and self.key is None:
            log.warning("ANOMALY_MODEL_KEY is not set: the risk models are not loaded")
            return False
        payload = self.store.load() if self.store is not None else None
        if payload is None:
            return False
        self.restore(unseal(payload, self.key))
        return True


class MlflowStore:
    """The bundle as an artifact of a run in the experiment, registered as a new version of REGISTERED."""

    def __init__(self, tracking_uri: str, experiment: str = "costguard-anomaly", client=None) -> None:
        if client is None:
            from mlflow.tracking import MlflowClient

            client = MlflowClient(tracking_uri=tracking_uri, registry_uri=tracking_uri)
        self.client = client
        self.experiment = experiment

    def _experiment_id(self) -> str:
        found = self.client.get_experiment_by_name(self.experiment)
        return found.experiment_id if found is not None else self.client.create_experiment(self.experiment)

    def save(self, payload: bytes, metrics: dict[str, float]) -> str:
        run = self.client.create_run(self._experiment_id(), tags={"costguard.model": REGISTERED})
        run_id = run.info.run_id
        for key, value in metrics.items():
            self.client.log_metric(run_id, key, value)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models.pkl"
            path.write_bytes(payload)
            self.client.log_artifact(run_id, str(path), artifact_path="bundle")
        self.client.set_terminated(run_id)
        try:
            self.client.create_registered_model(REGISTERED)
        except Exception as e:  # MLflow raises RESOURCE_ALREADY_EXISTS for a model that is there
            if "ALREADY_EXISTS" not in str(e):
                raise
        version = self.client.create_model_version(REGISTERED, f"runs:/{run_id}/bundle", run_id)
        return str(version.version)

    def load(self) -> bytes | None:
        versions = self.client.search_model_versions(f"name='{REGISTERED}'")
        if not versions:
            return None
        latest = max(versions, key=lambda v: int(v.version))
        with tempfile.TemporaryDirectory() as tmp:
            local = self.client.download_artifacts(latest.run_id, "bundle/models.pkl", tmp)
            return Path(local).read_bytes()


class MemoryStore:
    """For tests and for a detector without MLflow: versions kept in memory."""

    def __init__(self) -> None:
        self.versions: list[tuple[bytes, dict[str, float]]] = []

    def save(self, payload: bytes, metrics: dict[str, float]) -> str:
        self.versions.append((payload, metrics))
        return str(len(self.versions))

    def load(self) -> bytes | None:
        return self.versions[-1][0] if self.versions else None

