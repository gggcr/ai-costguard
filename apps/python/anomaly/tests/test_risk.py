"""The risk of agents: loops, spend spikes, suspicious purchases, the models and their storage in MLflow."""

import io
import random
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import fastavro
import pytest

from costguard_anomaly import models, risk, scorer, service
from costguard_common import schemas

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
AGENT = uuid.UUID("7f1c0d1e-0000-0000-0000-000000000001")
NOON = datetime(2026, 11, 5, 9, 0, tzinfo=UTC)  # 12:00 in Moscow


def op(at, cost="10", provider="OpenAI", kind="LLM_CALL", task=None, decision="ALLOWED", code=None, tokens=800,
       model="gpt-5-mini", simhash=None, agent=AGENT):
    return {"operation_id": str(uuid.uuid4()), "tenant_id": str(TENANT), "agent_id": str(agent), "task_id": task,
            "kind": kind, "provider": provider, "model": model if kind == "LLM_CALL" else None,
            "decision": decision, "denial_code": code, "estimated_cost": Decimal(cost),
            "actual_cost": Decimal(cost) if decision == "ALLOWED" else None, "currency": "RUB",
            "input_tokens": tokens, "output_tokens": 200, "mandate_version": 3, "prompt_simhash": simhash,
            "occurred_at": at}


def usual_day(s: scorer.Scorer, start=NOON - timedelta(hours=2), minutes=90):
    """An hour and a half of ordinary work: one call a minute, ten roubles, different prompts."""
    sizes = [500, 2100, 900, 3300, 1300, 2900, 1700, 4100, 2500, 3700]  # neither repeated nor only growing
    for i in range(minutes):
        assert s.add(op(start + timedelta(minutes=i), tokens=sizes[i % 10], task=f"t{i // 10}")) == []
    return start + timedelta(minutes=minutes)


def last(signals, task=None):
    found = [x for x in signals if x.task_id == task]
    return found[-1] if found else None


# loops


def test_the_same_call_repeated_in_a_task_stops_the_task_not_the_agent():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(10):
        out += s.add(op(t + timedelta(seconds=10 * i), task="loop-1", tokens=1200))
    stop = last(out, "loop-1")
    assert stop.action == "STOP" and stop.score >= 90
    assert "Петля в задаче loop-1: 10 одинаковых вызовов" in stop.reasons[0]
    assert last(out) is None  # the agent as a whole is fine


def test_five_repeats_send_the_task_to_a_person_and_simhash_counts_as_the_same_prompt():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(5):
        out += s.add(op(t + timedelta(seconds=10 * i), task="t-x", tokens=100 * (i + 3), simhash=0xABCDE0 + i))
    assert last(out, "t-x").action == "REQUIRE_APPROVAL"


def test_a_context_that_keeps_growing_is_a_loop():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(8):
        out += s.add(op(t + timedelta(seconds=20 * i), task="grow", tokens=1000 * 2 ** i))
    task = last(out, "grow")
    assert task.action == "SLOW_DOWN" and "контекст вырос в 128 раз" in task.reasons[0]


# spend spikes


def test_spend_far_above_the_usual_rate_slows_the_agent_then_stops_it():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(5):
        out += s.add(op(t + timedelta(seconds=30 * i), cost="100", tokens=9000 + i))
    slow = last(out)
    assert slow.action == "SLOW_DOWN" and "раз выше обычных" in slow.reasons[0]  # published on crossing 5×
    for i in range(5):
        out += s.add(op(t + timedelta(minutes=3, seconds=10 * i), cost="200", tokens=20000 + i))
    assert last(out).action == "STOP"


def test_a_new_agent_is_not_judged_by_a_baseline_it_does_not_have():
    s = scorer.Scorer()
    out = []
    for i in range(5):
        out += s.add(op(NOON + timedelta(seconds=30 * i), cost="1000", tokens=100 + i))
    assert out == []


# suspicious purchases


def test_a_purchase_split_into_similar_parts_goes_to_a_person():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    # three parts of about 30 at an agent that spends 10 at a time: split, but no spend spike
    for i, cost in enumerate(["30", "30", "31"]):
        out += s.add(op(t + timedelta(seconds=40 * i), cost=cost, provider="ООО Ландыш", kind="PAID_API"))
    signal = last(out)
    assert signal.action == "REQUIRE_APPROVAL"
    assert signal.reasons[0].startswith("Дробление покупки: 3 похожих платежей ООО Ландыш")


def test_a_new_vendor_at_night_for_a_day_agent():
    rules = risk.Rules()
    s = scorer.Scorer(rules)
    t = usual_day(s)
    night = datetime(2026, 11, 6, 0, 30, tzinfo=UTC)  # 03:30 in Moscow
    hits, _, _ = rules.observe(risk.Op.of(op(night, cost="40", provider="Облако Плюс", kind="PAID_API")))
    assert {h.rule for h in hits} == {"new-vendor", "night"}
    score, reasons = risk.combine(hits, 0.0, [])
    assert score == 51 and risk.action_of(score) == "SLOW_DOWN" and "ночью" in reasons[1]
    assert t < night


def test_many_errors_slow_the_agent_but_its_own_refusals_do_not():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(10):
        out += s.add(op(t + timedelta(seconds=5 * i), decision="DENIED" if i < 8 else "ALLOWED",
                        code="policy_denied" if i < 8 else None, tokens=3000 + 400 * i))
    assert last(out).action == "SLOW_DOWN" and "отклонены" in last(out).reasons[0]

    calm = scorer.Scorer()
    t = usual_day(calm)
    out = []
    for i in range(10):
        out += calm.add(op(t + timedelta(seconds=5 * i), decision="DENIED", code="agent_suspended",
                           tokens=3000 + 400 * i))
    assert last(out) is None


# signals


def test_a_calm_agent_gets_normal_back_after_the_window():
    s = scorer.Scorer()
    t = usual_day(s)
    out = []
    for i in range(10):
        out += s.add(op(t + timedelta(seconds=10 * i), task="loop-2", tokens=1200))
    assert s.tick(t + timedelta(minutes=2)) == []
    [normal] = s.tick(t + timedelta(minutes=7))
    assert (normal.task_id, normal.action, normal.score) == ("loop-2", "NORMAL", 0)
    assert s.tick(t + timedelta(minutes=8)) == []


def test_an_event_seen_twice_changes_nothing_and_small_moves_are_not_published():
    s = scorer.Scorer()
    t = usual_day(s)
    record = op(t, task="loop-3", tokens=1200)
    for i in range(9):
        s.add(op(t + timedelta(seconds=i), task="loop-3", tokens=1200))
    assert s.add(record) != []
    assert s.add(record) == []


def test_a_signal_fits_its_schema_and_is_keyed_by_agent_or_task():
    s = risk.Score(TENANT, AGENT, "t-1", 95, "STOP", ["Петля"], NOON)
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schemas.parsed("agent.risk-signals.v1"), scorer.signal(s))
    back = fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schemas.parsed("agent.risk-signals.v1"))
    assert back["action"] == "STOP" and back["score"] == 95
    assert service.signal_key(s) == f"{AGENT}:t-1"
    assert service.signal_key(risk.Score(TENANT, AGENT, None, 50, "SLOW_DOWN", [], NOON)) == str(AGENT)
    assert not service.worth_signalling(s, NOON + timedelta(minutes=11))


# combining


def test_without_a_rule_the_models_never_stop_an_agent():
    assert risk.combine([], 1.0, ["model"]) == (80, ["model"])
    assert risk.action_of(80) == "REQUIRE_APPROVAL"
    score, _ = risk.combine([risk.Hit("velocity", 60, "x")], 1.0, [])
    assert score == 100 and risk.action_of(score) == "STOP"
    assert risk.combine([risk.Hit("a", 60, "a"), risk.Hit("b", 40, "b")], 0.0, []) == (70, ["a", "b"])


def normal_features(rng):
    hour = rng.choice([10, 11, 12, 13, 14, 15])
    import math

    return {"log_cost": math.log1p(10 + rng.random()), "hour_sin": math.sin(2 * math.pi * hour / 24),
            "hour_cos": math.cos(2 * math.pi * hour / 24), "ops_1m": float(rng.randint(1, 2)),
            "ops_5m": float(rng.randint(3, 6)), "log_spend_5m": math.log1p(50 + 10 * rng.random()),
            "new_vendor": 0.0, "task_steps_5m": float(rng.randint(1, 5)), "log_input_tokens": math.log1p(800)}


def test_only_the_rules_count_until_the_models_learned_the_agent():
    rng = random.Random(1)
    m = models.Models()
    for _ in range(models.MIN_TRAIN - 1):
        assert m.score_and_learn(AGENT, normal_features(rng)) == (0.0, [])
    assert not m.of(AGENT).trained
    for _ in range(50):
        m.score_and_learn(AGENT, normal_features(rng))
    assert m.of(AGENT).forest is not None and m.of(AGENT).trained

    usual, _ = m.of(AGENT).score(normal_features(rng))
    odd = {**normal_features(rng), "log_cost": 12.0, "hour_sin": 0.5, "hour_cos": 0.87, "ops_1m": 40.0,
           "ops_5m": 150.0, "log_spend_5m": 14.0, "new_vendor": 1.0, "log_input_tokens": 11.0}
    strange, reasons = m.of(AGENT).score(odd)
    assert strange > 0.5 > usual
    assert any("Isolation Forest" in r for r in reasons)
    other = uuid.uuid4()
    assert m.score_and_learn(other, odd) == (0.0, [])  # another agent's models know nothing yet


def test_the_models_are_saved_and_loaded_as_versions():
    store = models.MemoryStore()
    rng = random.Random(2)
    m = models.Models(store, save_every=100, key=b"k3y")
    for _ in range(models.MIN_TRAIN + 10):
        m.score_and_learn(AGENT, normal_features(rng))
    assert len(store.versions) >= 2
    assert store.versions[-1][1]["agents_with_forest"] == 1.0

    again = service.scorer(service.AnomalySettings(anomaly_model_key="k3y"), store)
    assert again.models.of(AGENT).forest is not None


class Boom:
    """Unpickling it would run code: what a bundle planted in MLflow could do."""

    ran = False

    def __reduce__(self):
        return (Boom.mark, ())

    @staticmethod
    def mark():
        Boom.ran = True
        return {}


def test_a_bundle_not_signed_with_the_key_is_never_unpickled():
    import pickle

    store = models.MemoryStore()
    m = models.Models(store, key=b"k3y")
    planted = [pickle.dumps(Boom()),  # no signature at all
               models.seal(pickle.dumps(Boom()), b"other key"),  # signed with another key
               models.seal(pickle.dumps({}), b"k3y")[:-1] + pickle.dumps(Boom())]  # signed, then swapped
    for payload in planted:
        store.save(payload, {})
        with pytest.raises(ValueError):
            m.load()
    assert not Boom.ran

    # the detector starts with the rules alone rather than with that bundle
    scorer = service.scorer(service.AnomalySettings(anomaly_model_key="k3y"), store)
    assert scorer.models.agents == {} and not Boom.ran


def test_without_the_key_the_models_are_neither_saved_nor_loaded():
    store = models.MemoryStore()
    store.save(models.seal(pickle_of_nothing(), b"k3y"), {})
    m = models.Models(store, save_every=1)
    assert m.load() is False
    m.score_and_learn(AGENT, normal_features(random.Random(1)))
    assert len(store.versions) == 1


def pickle_of_nothing() -> bytes:
    import pickle

    return pickle.dumps({})


class FakeClient:
    """The few MlflowClient calls the store makes, in memory."""

    def __init__(self, tmp):
        self.tmp, self.runs, self.versions, self.registered, self.metrics = tmp, {}, [], set(), {}

    def get_experiment_by_name(self, name):
        return None if not self.runs else type("E", (), {"experiment_id": "1"})()

    def create_experiment(self, name):
        return "1"

    def create_run(self, experiment_id, tags):
        run_id = f"r{len(self.runs) + 1}"
        self.runs[run_id] = {}
        return type("R", (), {"info": type("I", (), {"run_id": run_id})()})()

    def log_metric(self, run_id, key, value):
        self.metrics[(run_id, key)] = value

    def log_artifact(self, run_id, path, artifact_path):
        from pathlib import Path

        self.runs[run_id][f"{artifact_path}/{Path(path).name}"] = Path(path).read_bytes()

    def set_terminated(self, run_id):
        pass

    def create_registered_model(self, name):
        if name in self.registered:
            raise RuntimeError("RESOURCE_ALREADY_EXISTS")
        self.registered.add(name)

    def create_model_version(self, name, source, run_id):
        self.versions.append(type("V", (), {"version": str(len(self.versions) + 1), "run_id": run_id,
                                            "source": source})())
        return self.versions[-1]

    def search_model_versions(self, query):
        return list(self.versions)

    def download_artifacts(self, run_id, path, dst):
        from pathlib import Path

        out = Path(dst) / "models.pkl"
        out.write_bytes(self.runs[run_id][path])
        return str(out)


def test_the_mlflow_store_registers_a_version_per_save_and_loads_the_latest(tmp_path):
    client = FakeClient(tmp_path)
    store = models.MlflowStore("http://mlflow", client=client)
    assert store.load() is None
    assert store.save(b"one", {"agents": 1.0}) == "1"
    assert store.save(b"two", {"agents": 2.0}) == "2"
    assert store.load() == b"two"
    assert client.versions[-1].source == "runs:/r2/bundle"
    assert client.metrics[("r2", "agents")] == 2.0 and client.registered == {models.REGISTERED}


def test_an_unreachable_mlflow_leaves_the_rules_working():
    class Down:
        def load(self):
            raise ConnectionError("mlflow down")

        def save(self, payload, metrics):
            raise ConnectionError("mlflow down")

    s = service.scorer(service.AnomalySettings(), Down())
    assert s.models.agents == {}


def test_a_negative_amount_or_token_count_does_not_break_the_scorer():
    s = scorer.Scorer()
    assert s.add(op(NOON, cost="-500", tokens=-3)) == []
