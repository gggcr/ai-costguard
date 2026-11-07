"""Checks that a changed Avro schema still reads what the old one wrote (backward compatibility).

    python tools/codegen/avro_compat.py old.avsc new.avsc

Writes generated records with the old schema and reads them with the new one, the way a consumer on the new
version reads messages already in the topic. A new field without a default, a removed enum symbol or a narrowed
type fails here before it fails in the Schema Registry.
"""

from __future__ import annotations

import io
import json
import sys

import fastavro
from fastavro.utils import generate_many


def check(old: dict, new: dict, count: int = 50) -> None:
    writer = fastavro.parse_schema(old)
    reader = fastavro.parse_schema(new)
    buffer = io.BytesIO()
    fastavro.writer(buffer, writer, list(generate_many(writer, count)))
    buffer.seek(0)
    for _ in fastavro.reader(buffer, reader_schema=reader):
        pass


def main(argv: list[str]) -> int:
    old_path, new_path = argv
    with open(old_path, encoding="utf-8") as f:
        old = json.load(f)
    with open(new_path, encoding="utf-8") as f:
        new = json.load(f)
    try:
        check(old, new)
    except Exception as e:  # fastavro raises SchemaResolutionError and friends
        print(f"{new_path} cannot read messages written with the previous version: {e}", file=sys.stderr)
        return 1
    print(f"{new_path}: reads the previous version")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
