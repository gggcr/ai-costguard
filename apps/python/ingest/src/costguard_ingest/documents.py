"""Reads any uploaded document into a table: tables and statements directly, scans through OCR (ADR 0012), and text
without a table (a PDF invoice, an OCR result) through invoice extraction (ADR 0013).

tables.read knows files; this module knows what to do when a file is a scan or plain text. Both steps are
optional: without OCR or without extraction the error says what is missing, and nothing crashes.
"""

from __future__ import annotations

import uuid
from typing import Protocol

from costguard_ingest import ocr, tables


class TextReader(Protocol):
    """Text in, table out. tenant and key (the stored file) let it cache and use the tenant's examples."""

    def __call__(self, file_name: str, text: str, tenant: uuid.UUID | None = None,
                 key: str | None = None) -> tables.Table: ...


class Reader:
    def __init__(self, engine: ocr.Ocr | None = None, from_text: TextReader | None = None) -> None:
        self.engine = engine
        self.from_text = from_text

    def __call__(self, file_name: str, content: bytes, tenant: uuid.UUID | None = None,
                 key: str | None = None) -> tables.Table:
        try:
            return tables.read(file_name, content)
        except tables.Scan as scan:
            cached = getattr(self.from_text, "cached", None)
            table = cached(key) if cached is not None else None
            if table is not None:  # read once already: accepting the file must not run OCR again
                return table
            return self._text(file_name, self._ocr(content, scan.is_pdf), tenant, key)
        except tables.TextOnly as only:
            return self._text(file_name, only.text, tenant, key)

    def _ocr(self, content: bytes, is_pdf: bool) -> str:
        if self.engine is None:
            raise tables.Unsupported("Scans need OCR, which is switched off here (INGEST_OCR_ENABLED=false)")
        try:
            text = ocr.read(self.engine, content, is_pdf)
        except ocr.OcrUnavailable as e:
            raise tables.Unsupported(str(e)) from e
        if not text:
            raise tables.Unsupported("OCR found no text in the scan")
        return text

    def _text(self, file_name: str, text: str, tenant: uuid.UUID | None, key: str | None) -> tables.Table:
        if self.from_text is None:
            raise tables.Unsupported("The document has text but no table; reading an invoice from text needs "
                                     "LLM extraction, which is off here (INGEST_LLM_API_KEY, INGEST_LLM_MODEL)")
        return self.from_text(file_name, text, tenant=tenant, key=key)
