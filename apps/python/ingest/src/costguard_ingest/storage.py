"""Where uploaded files are. The core writes them to local disk today (costguard.imports.storage-dir) and to S3 on
the board; the pipeline reads either, chosen by INGEST_STORAGE_DIR."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol


class Files(Protocol):
    def get(self, key: str) -> bytes: ...


class LocalFiles:
    def __init__(self, root: str | Path) -> None:
        self._root = Path(root).resolve()

    def get(self, key: str) -> bytes:
        path = (self._root / key).resolve()
        if not path.is_relative_to(self._root):
            raise ValueError(f"Key {key!r} points outside the storage directory")
        return path.read_bytes()


class S3Files:
    def __init__(self, endpoint: str, access_key: str, secret_key: str, bucket: str) -> None:
        import boto3

        self._bucket = bucket
        self._s3 = boto3.client("s3", endpoint_url=endpoint, aws_access_key_id=access_key,
                                aws_secret_access_key=secret_key)

    def get(self, key: str) -> bytes:
        return self._s3.get_object(Bucket=self._bucket, Key=key)["Body"].read()
