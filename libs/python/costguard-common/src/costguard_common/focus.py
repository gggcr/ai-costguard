"""FOCUS cost records, as cost.records.v1 carries them: one cost, whatever its source."""

from __future__ import annotations

import uuid
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal
from enum import StrEnum

from pydantic import BaseModel, field_validator

SCALE = Decimal("0.000001")


class CostSource(StrEnum):
    OPERATION = "OPERATION"
    INVOICE_LINE = "INVOICE_LINE"
    PAYMENT = "PAYMENT"
    SUBSCRIPTION = "SUBSCRIPTION"


class CostRecord(BaseModel):
    id: uuid.UUID
    tenant_id: uuid.UUID
    source: CostSource
    source_id: str
    billed_cost: Decimal
    effective_cost: Decimal
    billing_currency: str
    charge_period_start: date
    charge_period_end: date
    service_provider_name: str
    department_id: uuid.UUID | None = None

    @field_validator("billed_cost", "effective_cost")
    @classmethod
    def six_places(cls, v: Decimal) -> Decimal:
        return v.quantize(SCALE, rounding=ROUND_HALF_EVEN)

    @field_validator("billing_currency")
    @classmethod
    def iso_code(cls, v: str) -> str:
        if len(v) != 3 or not v.isalpha():
            raise ValueError("currency must be an ISO 4217 code")
        return v.upper()

    @staticmethod
    def stable_id(source: CostSource, source_id: str) -> uuid.UUID:
        """Same input, same id: a re-imported invoice line replaces its record instead of adding one."""
        return uuid.uuid5(uuid.NAMESPACE_URL, f"costguard:{source}:{source_id}")

    def avro(self) -> dict:
        return {**self.model_dump(), "source": self.source.value, "id": str(self.id),
                "tenant_id": str(self.tenant_id),
                "department_id": None if self.department_id is None else str(self.department_id)}
