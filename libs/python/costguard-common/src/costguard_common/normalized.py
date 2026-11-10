"""ingest.normalized.v1: one vendor, employee, subscription, payment... as a source saw it.

The entity key is what makes two sources agree. Vendors are keyed by INN when it is known and by the cleaned name
otherwise; people by e-mail, then by the cleaned full name. So the same supplier from 1C, МойСклад and an uploaded
invoice lands on one record in the core.
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

TOPIC = "ingest.normalized.v1"


class EntityType(StrEnum):
    VENDOR = "VENDOR"
    SUBSCRIPTION = "SUBSCRIPTION"
    CONTRACT = "CONTRACT"
    INVOICE = "INVOICE"
    PAYMENT = "PAYMENT"
    EMPLOYEE = "EMPLOYEE"
    LICENSE_ASSIGNMENT = "LICENSE_ASSIGNMENT"
    ACTIVITY_EVENT = "ACTIVITY_EVENT"
    ASSET = "ASSET"


_LEGAL_FORMS = re.compile(
    r"\b(ооо|оао|зао|пао|ао|ип|нко|ano|llc|ltd|inc|gmbh|corp|co|limited|общество с ограниченной ответственностью|"
    r"акционерное общество|публичное акционерное общество|индивидуальный предприниматель)\b")


def clean_name(name: str) -> str:
    """'ООО «Яндекс»' and 'Yandex LLC'-style spellings of one name to one key: no legal form, quotes or case."""
    text = name.lower().replace("ё", "е")
    text = re.sub(r"[\"'«»“”„`.,()]", " ", text)
    text = _LEGAL_FORMS.sub(" ", text)
    return " ".join(text.split())


def clean_inn(inn: object) -> str | None:
    digits = re.sub(r"\D", "", "" if inn is None else str(inn))
    return digits if len(digits) in (10, 12) else None


def clean_email(email: object) -> str | None:
    text = "" if email is None else str(email).strip().lower()
    return text if re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", text) else None


def vendor_key(tenant: uuid.UUID, name: str, inn: object = None) -> str:
    code = clean_inn(inn)
    return f"{tenant}:vendor:inn:{code}" if code else f"{tenant}:vendor:name:{clean_name(name)}"


def person_key(tenant: uuid.UUID, email: object = None, name: str = "") -> str:
    address = clean_email(email)
    return f"{tenant}:employee:email:{address}" if address else f"{tenant}:employee:name:{clean_name(name)}"


def key(tenant: uuid.UUID, kind: str, *parts: str) -> str:
    return f"{tenant}:{kind}:" + ":".join(p.strip().lower() for p in parts)


@dataclass(frozen=True)
class Entity:
    """What a source found, before it is tied to a tenant and a file."""

    type: EntityType
    key: str
    payload: dict
    confidence: float = 1.0

    def message(self, tenant: uuid.UUID, file_id: uuid.UUID, at: datetime) -> dict:
        return {
            "entity_key": self.key, "tenant_id": str(tenant), "file_id": str(file_id), "entity_type": self.type.value,
            "payload": json.dumps(self.payload, ensure_ascii=False, default=str, sort_keys=True),
            "confidence": self.confidence, "normalized_at": at,
        }


ASSET_TYPES = ("LAPTOP", "PHONE", "MONITOR", "OTHER")
_ASSET_WORDS = (
    ("LAPTOP", ("laptop", "notebook", "ноутбук", "macbook", "thinkpad", "ultrabook", "лэптоп")),
    ("PHONE", ("phone", "iphone", "smartphone", "телефон", "смартфон", "galaxy", "pixel")),
    ("MONITOR", ("monitor", "монитор", "display", "дисплей")),
)


def asset_kind(*texts: object) -> str:
    """The kind of a device from its type, name or model as people write them; OTHER when nothing says."""
    text = " ".join(str(t) for t in texts if t).lower().replace("ё", "е")
    if not text:
        return "OTHER"
    for kind in ASSET_TYPES:
        if text.strip().upper() == kind:
            return kind
    for kind, words in _ASSET_WORDS:
        if any(w in text for w in words):
            return kind
    return "OTHER"
