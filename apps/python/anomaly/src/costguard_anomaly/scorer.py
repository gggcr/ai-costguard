"""Turns operations into risk signals (agent.risk-signals.v1): rules, models, the score and when to tell the gateway.

A signal is published when its action changes, or its score moves by SCORE_STEP or more; the topic is compacted,
so the gateway keeps the latest one. An agent or task that is not NORMAL is scored again by `tick` once its window
has passed, so a calm agent gets NORMAL back without waiting for its next operation.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from costguard_anomaly import models as modelling
from costguard_anomaly.risk import WINDOW, Op, Rules, Score, action_of, combine

SCORE_STEP = 10


@dataclass
class _Published:
    score: int
    action: str
    at: datetime
    tenant: uuid.UUID


class Scorer:
    def __init__(self, rules: Rules | None = None, models: modelling.Models | None = None) -> None:
        self.rules = rules or Rules()
        self.models = models or modelling.Models()
        self.published: dict[tuple[uuid.UUID, str | None], _Published] = {}
        self.seen: set[str] = set()

    def add(self, record: dict) -> list[Score]:
        """One agent.operations.v1 record; the signals to publish. The same event twice changes nothing."""
        op = Op.of(record)
        event = f"{op.operation_id}:{op.decision}:{op.at.isoformat()}"
        if event in self.seen:
            return []
        self.seen.add(event)
        agent_hits, task_hits, features = self.rules.observe(op)
        model, model_reasons = self.models.score_and_learn(op.agent_id, features)
        out = []
        score, reasons = combine(agent_hits, model, model_reasons)
        out += self._maybe(op.tenant_id, op.agent_id, None, score, reasons, op.at)
        if op.task_id is not None:
            task_score, task_reasons = combine(task_hits, 0.0, [])
            out += self._maybe(op.tenant_id, op.agent_id, op.task_id, task_score, task_reasons, op.at)
        return out

    def _maybe(self, tenant, agent, task, score, reasons, at) -> list[Score]:
        action = action_of(score)
        last = self.published.get((agent, task))
        if last is None and action == "NORMAL":
            return []
        if last is not None and last.action == action and abs(last.score - score) < SCORE_STEP:
            return []
        self.published[(agent, task)] = _Published(score, action, at, tenant)
        return [Score(tenant, agent, task, score, action, reasons, at)]

    def tick(self, now: datetime) -> list[Score]:
        """NORMAL again for agents and tasks whose last signal is older than the window and not NORMAL."""
        out = []
        for (agent, task), last in list(self.published.items()):
            if last.action != "NORMAL" and now - last.at > WINDOW:
                self.published[(agent, task)] = _Published(0, "NORMAL", now, last.tenant)
                out.append(Score(last.tenant, agent, task, 0, "NORMAL", ["Последние 5 минут без отклонений"], now))
        return out

    def forget_events(self, keep: int = 200_000) -> None:
        if len(self.seen) > keep:
            self.seen.clear()


def signal(s: Score) -> dict:
    return {"tenant_id": str(s.tenant_id), "agent_id": str(s.agent_id), "task_id": s.task_id, "score": s.score,
            "action": s.action, "reasons": s.reasons, "detected_at": s.at}
