"""Finds anomalies in cost records, without Kafka, so it can be tested on plain records.

Two rules:

- anomaly.spike: one provider's spend on one day is far above its usual day. The usual day is the median of the
  previous days that had spend (at least MIN_HISTORY of them, within HISTORY_DAYS); "far" is a robust z-score,
  (day - median) / (1.4826 * MAD), above Z_LIMIT, and at least MIN_RATIO times the median. The expected saving is
  the excess over the median. Agent spend is checked the same way, per agent.
- anomaly.double-charge: the same provider charged the same amount on the same day under two different source
  ids, for invoice lines and payments (agent operations repeat amounts by nature and are left out).

Each day's total grows while records arrive, so a spike is reported when the running total crosses the limit and
reported again (same fingerprint, new amount) as it grows; the core refreshes the finding.
"""

from __future__ import annotations

import statistics
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal

HISTORY_DAYS = 60
MIN_HISTORY = 7
Z_LIMIT = 4.0
MIN_RATIO = Decimal(2)
MAD_SCALE = 1.4826


@dataclass(frozen=True)
class Anomaly:
    tenant_id: uuid.UUID
    rule: str
    fingerprint: str
    title: str
    expected_savings: Decimal
    currency: str
    entity_ids: list[str]
    day: date


@dataclass
class _Series:
    days: dict[date, Decimal] = field(default_factory=lambda: defaultdict(Decimal))


class Detector:
    def __init__(self) -> None:
        self._series: dict[tuple[uuid.UUID, str, str], _Series] = defaultdict(_Series)
        self._charges: dict[tuple[uuid.UUID, str, date, Decimal, str], set[str]] = defaultdict(set)
        self._seen: set[uuid.UUID] = set()

    def add(self, record: dict) -> list[Anomaly]:
        """One cost.records.v1 record, as fastavro reads it. A record seen before (same id) changes nothing."""
        rid = uuid.UUID(str(record["id"]))
        if rid in self._seen:
            return []
        self._seen.add(rid)
        tenant = uuid.UUID(str(record["tenant_id"]))
        provider = record["service_provider_name"]
        currency = record["billing_currency"]
        amount = Decimal(record["billed_cost"])
        day = record["charge_period_start"]
        found: list[Anomaly] = []

        series = self._series[(tenant, provider, currency)]
        series.days[day] += amount
        spike = self._spike(tenant, provider, currency, series, day, record)
        if spike:
            found.append(spike)

        if record["source"] in ("INVOICE_LINE", "PAYMENT") and amount > 0:
            same = self._charges[(tenant, provider, day, amount, currency)]
            same.add(record["source_id"])
            if len(same) >= 2:
                ids = sorted(same)
                found.append(Anomaly(
                    tenant, "anomaly.double-charge",
                    f"anomaly.double-charge:{provider}:{day}:{amount}:{currency}",
                    f"Возможно двойное списание: {provider}, {amount} {currency} {day:%d.%m.%Y}, {len(ids)} раза",
                    amount * (len(ids) - 1), currency, [f"cost:{s}" for s in ids], day))
        return found

    def _spike(self, tenant, provider, currency, series: _Series, day: date, record: dict) -> Anomaly | None:
        start = day - timedelta(days=HISTORY_DAYS)
        history = [v for d, v in series.days.items() if start <= d < day and v > 0]
        if len(history) < MIN_HISTORY:
            return None
        median = Decimal(str(statistics.median(history)))
        mad = Decimal(str(statistics.median([abs(v - median) for v in history])))
        total = series.days[day]
        spread = mad * Decimal(str(MAD_SCALE)) or median * Decimal("0.1") or Decimal(1)
        if (total - median) / spread <= Decimal(str(Z_LIMIT)) or total < median * MIN_RATIO:
            return None
        entities = [f"provider:{provider}"]
        if record["source"] == "OPERATION" and provider.startswith("agent "):
            entities.append(f"agent:{provider.removeprefix('agent ')}")
        return Anomaly(
            tenant, "anomaly.spike", f"anomaly.spike:{provider}:{currency}:{day}",
            f"Всплеск трат: {provider}, {total} {currency} за {day:%d.%m.%Y} при обычных {median}",
            total - median, currency, entities, day)

    def forget_before(self, day: date) -> None:
        """Drops what no rule looks at any more, so memory stays bounded."""
        limit = day - timedelta(days=HISTORY_DAYS + 1)
        for series in self._series.values():
            for d in [d for d in series.days if d < limit]:
                del series.days[d]
        for key in [k for k in self._charges if k[2] < limit]:
            del self._charges[key]
