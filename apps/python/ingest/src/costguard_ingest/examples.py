"""Invoices people corrected, kept as examples for their vendor (ADR 0013).

Examples live in the pipeline's own storage, per tenant, never shared between tenants:

    <tenant>/examples/index.json        vendor slug -> {"name", "inn"}
    <tenant>/examples/<slug>.json       the last KEEP examples: {"text", "result"}

A new document is matched to a vendor by an INN in its text or by the vendor's cleaned name, and the model sees
that vendor's latest corrected invoice before the new one.
"""

from __future__ import annotations

import hashlib
import json
import re
import uuid
from dataclasses import dataclass

from costguard_common import normalized
from costguard_ingest import storage

KEEP = 3
SHOWN = 2
TEXT = 4000


@dataclass(frozen=True)
class Example:
    text: str
    result: dict


def slug(name: str, inn: str | None) -> str:
    code = normalized.clean_inn(inn)
    basis = f"inn:{code}" if code else f"name:{normalized.clean_name(name)}"
    return hashlib.sha256(basis.encode("utf-8")).hexdigest()[:24]


class Examples:
    def __init__(self, files: storage.Files) -> None:
        self.files = files

    def _read(self, key: str, default):
        try:
            return json.loads(self.files.get(key))
        except (FileNotFoundError, ValueError):
            return default

    def _write(self, key: str, value) -> None:
        self.files.put(key, json.dumps(value, ensure_ascii=False).encode("utf-8"))

    def add(self, tenant: uuid.UUID, vendor: str, inn: str | None, text: str, result: dict) -> None:
        s = slug(vendor, inn)
        index = self._read(f"{tenant}/examples/index.json", {})
        index[s] = {"name": vendor, "inn": normalized.clean_inn(inn)}
        kept = self._read(f"{tenant}/examples/{s}.json", [])
        kept = (kept + [{"text": text[:TEXT], "result": result}])[-KEEP:]
        self._write(f"{tenant}/examples/{s}.json", kept)
        self._write(f"{tenant}/examples/index.json", index)

    def matching(self, tenant: uuid.UUID, text: str) -> list[Example]:
        index = self._read(f"{tenant}/examples/index.json", {})
        if not index:
            return []
        inns = set(re.findall(r"(?<!\d)(\d{12}|\d{10})(?!\d)", text))
        cleaned = f" {normalized.clean_name(text)} "
        found = []
        for s, vendor in index.items():
            by_inn = vendor.get("inn") and vendor["inn"] in inns
            name = normalized.clean_name(vendor.get("name") or "")
            by_name = len(name) >= 3 and f" {name} " in cleaned
            if by_inn or by_name:
                kept = self._read(f"{tenant}/examples/{s}.json", [])
                if kept:
                    found.append((0 if by_inn else 1, Example(kept[-1]["text"], kept[-1]["result"])))
        return [e for _, e in sorted(found, key=lambda x: x[0])][:SHOWN]
