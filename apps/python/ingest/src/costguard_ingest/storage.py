"""Where uploaded files are. The core writes them to local disk today (costguard.imports.storage-dir) and to S3 on
the board; the pipeline reads either, chosen by INGEST_STORAGE_DIR.

The pipeline writes only its own files next to them: what LLM extraction read from a document, and the examples
people corrected (ADR 0013). It never changes an uploaded file."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol


class Files(Protocol):
    def get(self, key: str) -> bytes: ...

    def put(self, key: str, content: bytes) -> None: ...


class LocalFiles:
    def __init__(self, root: str | Path) -> None:
        self._root = Path(root).resolve()

    def _path(self, key: str) -> Path:
        path = (self._root / key).resolve()
        if not path.is_relative_to(self._root):
            raise ValueError(f"Key {key!r} points outside the storage directory")
        return path

    def get(self, key: str) -> bytes:
        return self._path(key).read_bytes()

    def put(self, key: str, content: bytes) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_bytes(content)
        tmp.replace(path)


class S3Files:
    def __init__(self, endpoint: str, access_key: str, secret_key: str, bucket: str,
                 region: str = "ru-central1") -> None:
        import boto3

        self._bucket = bucket
        self._s3 = boto3.client("s3", endpoint_url=endpoint, region_name=region, aws_access_key_id=access_key,
                                aws_secret_access_key=secret_key)

    def get(self, key: str) -> bytes:
        try:
            return self._s3.get_object(Bucket=self._bucket, Key=key)["Body"].read()
        except self._s3.exceptions.NoSuchKey as e:
            raise FileNotFoundError(key) from e

    def put(self, key: str, content: bytes) -> None:
        self._s3.put_object(Bucket=self._bucket, Key=key, Body=content)
