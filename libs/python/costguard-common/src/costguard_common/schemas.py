"""The Kafka contracts live in schemas/avro; Python reads the same files Java generates classes from."""

from __future__ import annotations

import json
import os
from functools import cache
from pathlib import Path

import fastavro

ROOT = Path(__file__).resolve().parents[5]
# COSTGUARD_SCHEMA_DIR is for installed copies (the Docker image), where the source tree is not around.
SCHEMA_DIR = Path(os.environ.get("COSTGUARD_SCHEMA_DIR") or ROOT / "schemas" / "avro")


@cache
def raw(topic: str) -> dict:
    return json.loads((SCHEMA_DIR / f"{topic}.avsc").read_text(encoding="utf-8"))


@cache
def parsed(topic: str):
    return fastavro.parse_schema(raw(topic))


def topics() -> list[str]:
    return sorted(p.stem for p in SCHEMA_DIR.glob("*.avsc"))
