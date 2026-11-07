"""Entity builders, so every connector spells payloads and keys the same way."""

from __future__ import annotations

import uuid
from datetime import date
from decimal import Decimal

from costguard_common import normalized
from costguard_common.focus import CostRecord, CostSource
from costguard_common.normalized import Entity, EntityType


def vendor(tenant: uuid.UUID, name: str, inn: object = None, source: str = "", **extra) -> Entity:
    code = normalized.clean_inn(inn)
    return Entity(EntityType.VENDOR, normalized.vendor_key(tenant, name, code),
                  {"name": name.strip(), "inn": code, "source": source, **extra})


def employee(tenant: uuid.UUID, name: str, email: object = None, department: str | None = None,
             active: bool = True, source: str = "", **extra) -> Entity:
    address = normalized.clean_email(email)
    return Entity(EntityType.EMPLOYEE, normalized.person_key(tenant, address, name),
                  {"name": " ".join(name.split()) or None, "email": address, "department": department,
                   "active": active, "source": source, **extra})


def subscription(tenant: uuid.UUID, vendor_name: str, product: str, seats: int | None = None,
                 source: str = "") -> Entity:
    return Entity(EntityType.SUBSCRIPTION, normalized.key(tenant, "subscription", vendor_name, product),
                  {"vendor": vendor_name, "product": product, "seats": seats, "source": source})


def license_assignment(tenant: uuid.UUID, vendor_name: str, product: str, person: Entity,
                       last_active: date | None, source: str = "") -> Entity:
    who = person.payload.get("email") or person.payload.get("name") or ""
    return Entity(EntityType.LICENSE_ASSIGNMENT, normalized.key(tenant, "license", vendor_name, product, who),
                  {"vendor": vendor_name, "product": product, "employee": who, "employee_key": person.key,
                   "last_active": last_active, "source": source})


def invoice(tenant: uuid.UUID, number: str, vendor_entity: Entity, total: Decimal, currency: str, day: date,
            source: str) -> Entity:
    return Entity(EntityType.INVOICE, normalized.key(tenant, "invoice", source, number),
                  {"number": number, "vendor": vendor_entity.payload["name"], "vendor_key": vendor_entity.key,
                   "total": str(total), "currency": currency, "date": day, "source": source})


def cost(tenant: uuid.UUID, source: str, document_id: str, amount: Decimal, currency: str, day: date,
         provider: str, kind: CostSource = CostSource.INVOICE_LINE) -> CostRecord:
    sid = f"{source}:{document_id}"
    return CostRecord(id=CostRecord.stable_id(kind, sid), tenant_id=tenant, source=kind, source_id=sid,
                      billed_cost=amount, effective_cost=amount, billing_currency=currency, charge_period_start=day,
                      charge_period_end=day, service_provider_name=provider)
