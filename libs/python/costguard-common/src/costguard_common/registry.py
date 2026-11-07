"""Schema Registry: register under <topic>-value, look up by id. Both are cached."""

from __future__ import annotations

import json
import threading

import fastavro
import httpx


class SchemaRegistry:
    def __init__(self, url: str, client: httpx.Client | None = None) -> None:
        self._url = url.rstrip("/")
        self._http = client or httpx.Client(timeout=10)
        self._ids: dict[tuple[str, str], int] = {}
        self._schemas: dict[int, object] = {}
        self._lock = threading.Lock()

    def id_of(self, topic: str, schema: dict) -> int:
        text = json.dumps(schema, separators=(",", ":"), sort_keys=True)
        key = (topic, text)
        with self._lock:
            if key in self._ids:
                return self._ids[key]
        response = self._http.post(
            f"{self._url}/subjects/{topic}-value/versions",
            content=json.dumps({"schema": json.dumps(schema)}),
            headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
        )
        if response.status_code != 200:
            raise RuntimeError(f"Schema Registry refused {topic}: {response.status_code} {response.text}")
        schema_id = int(response.json()["id"])
        with self._lock:
            self._ids[key] = schema_id
        return schema_id

    def schema_of(self, schema_id: int):
        with self._lock:
            if schema_id in self._schemas:
                return self._schemas[schema_id]
        response = self._http.get(f"{self._url}/schemas/ids/{schema_id}")
        if response.status_code != 200:
            raise RuntimeError(f"Schema Registry has no schema {schema_id}: {response.status_code}")
        schema = fastavro.parse_schema(json.loads(response.json()["schema"]))
        with self._lock:
            self._schemas[schema_id] = schema
        return schema
