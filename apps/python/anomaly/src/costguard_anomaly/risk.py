"""The risk of an agent from its operations (agent.operations.v1), as on board frame 4 (ADR 0016).

Per agent the scorer keeps the last minutes of operations and a long baseline. From them come the features of
windows of 1 and 5 minutes, per task and per agent, and the rules:

- loops (per task): many steps in a few minutes, the same call repeated (same provider, model and about the same
  prompt size, or the same prompt_simhash when the gateway sends one), a context that keeps growing;
- spend velocity: spend of the last 5 minutes against the agent's usual rate;
- suspicious purchases: a vendor the agent never paid, a purchase split into similar parts, activity at night
  when the agent does not work at night, many errors.

Each rule gives points and a reason people can read. Models (costguard_anomaly.models) add points once trained on
the agent's own data; until then only the rules count. The score is the strongest rule plus a quarter of the
others plus the models, at most 100. Without a rule the score stops below STOP: a model alone never stops an
agent, it can only send its operations to a person.

Loop rules score the task (a signal with task_id), the others the agent; the gateway applies the stricter of the
two. Refusals the risk itself caused (agent_suspended, rate_limited, approval) do not count as errors, so a stop
does not keep itself going.
"""

from __future__ import annotations

import math
import statistics
import uuid
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

WINDOW = timedelta(minutes=5)
SHORT = timedelta(minutes=1)
BASELINE_MINUTES = 7 * 24 * 60
OWN_REFUSALS = {"agent_suspended", "rate_limited", "approval_required", "risk"}

ACTIONS = (("STOP", 90), ("REQUIRE_APPROVAL", 70), ("SLOW_DOWN", 50))
MODEL_POINTS = 80  # models alone reach REQUIRE_APPROVAL at most: the cap without a rule is 89


@dataclass(frozen=True)
class Op:
    """The fields of an agent.operations.v1 record the scorer uses."""

    operation_id: str
    tenant_id: uuid.UUID
    agent_id: uuid.UUID
    task_id: str | None
    kind: str
    provider: str
    model: str | None
    decision: str
    denial_code: str | None
    cost: Decimal
    input_tokens: int | None
    simhash: int | None
    at: datetime

    @staticmethod
    def of(record: dict) -> Op:
        actual = record.get("actual_cost")
        cost = Decimal(str(actual if actual is not None else record.get("estimated_cost") or 0))
        at = record["occurred_at"]
        return Op(str(record["operation_id"]), uuid.UUID(str(record["tenant_id"])),
                  uuid.UUID(str(record["agent_id"])), record.get("task_id"), str(record["kind"]),
                  str(record["provider"]), record.get("model"), str(record["decision"]), record.get("denial_code"),
                  cost, record.get("input_tokens"), record.get("prompt_simhash"), at)

    @property
    def spent(self) -> Decimal:
        return self.cost if self.decision == "ALLOWED" else Decimal(0)

    @property
    def error(self) -> bool:
        return self.decision == "DENIED" and (self.denial_code or "") not in OWN_REFUSALS

    def signature(self) -> tuple:
        """Two calls that look the same: the simhash when there is one, else provider, model and prompt size."""
        if self.simhash is not None:
            return ("h", self.simhash >> 4)
        size = round(math.log(self.input_tokens, 1.1)) if self.input_tokens and self.input_tokens > 0 else None
        return ("p", self.provider, self.model, size)


@dataclass(frozen=True)
class Hit:
    rule: str
    points: int
    reason: str


@dataclass(frozen=True)
class Score:
    tenant_id: uuid.UUID
    agent_id: uuid.UUID
    task_id: str | None
    score: int
    action: str
    reasons: list[str]
    at: datetime


def _log(value) -> float:
    return math.log1p(max(0.0, float(value)))


def action_of(score: int) -> str:
    return next((name for name, limit in ACTIONS if score >= limit), "NORMAL")


def combine(hits: list[Hit], model: float, model_reasons: list[str],
            cap_without_rules: int = 89) -> tuple[int, list[str]]:
    """The strongest rule, a quarter of the others, and up to MODEL_POINTS for the models (0..1)."""
    points = sorted((h.points for h in hits), reverse=True)
    rules = (points[0] + sum(points[1:]) / 4) if points else 0
    total = min(100, round(rules + MODEL_POINTS * max(0.0, min(1.0, model))))
    if not hits:
        total = min(total, cap_without_rules)
    reasons = [h.reason for h in sorted(hits, key=lambda h: -h.points)] + model_reasons
    return total, reasons


@dataclass
class Agent:
    recent: deque[Op] = field(default_factory=deque)
    minutes: dict[datetime, Decimal] = field(default_factory=lambda: defaultdict(Decimal))
    vendors: Counter = field(default_factory=Counter)
    costs: deque[Decimal] = field(default_factory=lambda: deque(maxlen=500))
    hours: Counter = field(default_factory=Counter)
    seen: int = 0


class Rules:
    def __init__(self, zone: str = "Europe/Moscow", min_history: int = 50) -> None:
        self.zone = ZoneInfo(zone)
        self.min_history = min_history
        self.agents: dict[uuid.UUID, Agent] = defaultdict(Agent)

    def observe(self, op: Op) -> tuple[list[Hit], list[Hit], dict[str, float]]:
        """Adds one operation; returns the agent's hits, the task's hits and the features for the models."""
        a = self.agents[op.agent_id]
        a.recent.append(op)
        while a.recent and op.at - a.recent[0].at > WINDOW:
            a.recent.popleft()
        window = list(a.recent)
        short = [o for o in window if op.at - o.at <= SHORT]
        task = [o for o in window if op.task_id is not None and o.task_id == op.task_id]
        known_vendor = a.vendors[op.provider] > 0
        experienced = a.seen >= self.min_history
        local_hour = op.at.astimezone(self.zone).hour

        agent_hits: list[Hit] = []
        task_hits: list[Hit] = []
        if task:
            task_hits += self._loops(op, task)
        spend_5m = sum((o.spent for o in window), Decimal(0))
        usual = self._usual_per_minute(a, op.at)
        if experienced and usual > 0 and spend_5m > 0:
            ratio = float(spend_5m / (usual * 5))
            if ratio >= 20:
                agent_hits.append(Hit("velocity", 90, f"Траты за 5 минут ({spend_5m}) в {ratio:.0f} раз выше обычных"))
            elif ratio >= 5:
                agent_hits.append(Hit("velocity", 60, f"Траты за 5 минут ({spend_5m}) в {ratio:.0f} раз выше обычных"))
        median = Decimal(str(statistics.median(a.costs))) if a.costs else Decimal(0)
        if experienced and not known_vendor and op.cost > 0:
            big = median > 0 and op.cost >= median * 3
            reason = f"Новый поставщик для агента: {op.provider}"
            if big:
                reason += f", сумма {op.cost} при обычных {median}"
            agent_hits.append(Hit("new-vendor", 45 if big else 20, reason))
        split = [o for o in window if o.provider == op.provider and o.kind == "PAID_API" and o.cost > 0]
        if op.kind == "PAID_API" and len(split) >= 3 and median > 0:
            low, high = min(o.cost for o in split), max(o.cost for o in split)
            total = sum((o.cost for o in split), Decimal(0))
            if high <= low * Decimal("1.15") and total >= median * 5:
                agent_hits.append(Hit("splitting", 75, f"Дробление покупки: {len(split)} похожих платежей "
                                                       f"{op.provider} за 5 минут, всего {total}"))
        night_share = sum(a.hours[h] for h in range(6)) / max(1, sum(a.hours.values()))
        if experienced and local_hour < 6 and night_share < 0.05:
            agent_hits.append(Hit("night", 25, f"Операция ночью ({local_hour}:00), агент обычно ночью не работает"))
        errors = [o for o in window if o.error]
        if len(window) >= 10 and len(errors) / len(window) >= 0.5:
            agent_hits.append(Hit("errors", 50, f"{len(errors)} из {len(window)} операций за 5 минут отклонены"))

        # a refund or a broken record may carry a negative amount: the logarithms take none
        features = {
            "log_cost": _log(op.cost), "hour_sin": math.sin(2 * math.pi * local_hour / 24),
            "hour_cos": math.cos(2 * math.pi * local_hour / 24), "ops_1m": float(len(short)),
            "ops_5m": float(len(window)), "log_spend_5m": _log(spend_5m),
            "new_vendor": 0.0 if known_vendor else 1.0, "task_steps_5m": float(len(task)),
            "log_input_tokens": _log(op.input_tokens or 0),
        }
        # learn after scoring: the operation is judged against what was known before it
        a.seen += 1
        a.vendors[op.provider] += 1
        if op.cost > 0:
            a.costs.append(op.cost)
        a.hours[local_hour] += 1
        a.minutes[op.at.replace(second=0, microsecond=0)] += op.spent
        return agent_hits, task_hits, features

    def _loops(self, op: Op, task: list[Op]) -> list[Hit]:
        hits = []
        repeats = Counter(o.signature() for o in task)[op.signature()]
        if repeats >= 10:
            hits.append(Hit("loop", 95, f"Петля в задаче {op.task_id}: {repeats} одинаковых вызовов за 5 минут"))
        elif repeats >= 5:
            hits.append(Hit("loop", 70, f"Похоже на петлю в задаче {op.task_id}: {repeats} одинаковых вызовов"))
        if len(task) >= 50:
            hits.append(Hit("loop-steps", 60, f"Задача {op.task_id}: {len(task)} шагов за 5 минут"))
        tokens = [o.input_tokens for o in task if o.input_tokens]
        if len(tokens) >= 8 and tokens[0] > 0 and tokens[-1] >= tokens[0] * 4 and tokens == sorted(tokens):
            hits.append(Hit("loop-context", 55, f"Задача {op.task_id}: контекст вырос в {tokens[-1] / tokens[0]:.0f} "
                                                f"раз за {len(tokens)} шагов"))
        return hits

    def _usual_per_minute(self, a: Agent, now: datetime) -> Decimal:
        """Average spend per active minute over the baseline, the last 5 minutes left out."""
        cutoff = now - WINDOW
        start = now - timedelta(minutes=BASELINE_MINUTES)
        for m in [m for m in a.minutes if m < start]:
            del a.minutes[m]
        values = [v for m, v in a.minutes.items() if m < cutoff.replace(second=0, microsecond=0) and v > 0]
        return sum(values, Decimal(0)) / len(values) if len(values) >= 10 else Decimal(0)

    def has(self, agent: uuid.UUID) -> bool:
        return agent in self.agents
