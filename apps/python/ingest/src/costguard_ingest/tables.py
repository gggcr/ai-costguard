"""Turns an uploaded file into a header and rows. Row numbers are the ones people see: the header is row 1."""

from __future__ import annotations

import csv
import io
from dataclasses import dataclass, field, replace

from costguard_ingest import statements


class Unsupported(Exception):
    """The pipeline cannot read this kind of file yet."""


class Scan(Unsupported):
    """A scanned PDF or a photo: there is no text until OCR reads it (documents.Reader)."""

    def __init__(self, message: str, is_pdf: bool) -> None:
        super().__init__(message)
        self.is_pdf = is_pdf


class TextOnly(Unsupported):
    """A PDF with text but no table, such as most invoices: the text needs extracting (documents.Reader)."""

    def __init__(self, message: str, text: str) -> None:
        super().__init__(message)
        self.text = text


@dataclass(frozen=True)
class Table:
    columns: list[str]
    rows: list[tuple[int, dict[str, object]]]
    # "invoice" for invoices and exports, "statement" for bank statements: their rows are payments.
    kind: str = "invoice"
    # Cells a person should check, (row, column) to confidence: what LLM extraction was unsure of.
    review: dict[tuple[int, str], float] = field(default_factory=dict)


IMAGES = (".jpg", ".jpeg", ".png", ".heic", ".tif", ".tiff", ".webp", ".bmp")


def read(file_name: str, content: bytes) -> Table:
    name = file_name.lower()
    if statements.is_statement(content):
        return replace(_table(statements.lines(content)), kind="statement")
    if name.endswith((".csv", ".txt")):
        return _csv(content)
    if name.endswith((".xlsx", ".xlsm")):
        return _xlsx(content)
    if name.endswith(".parquet"):
        return _parquet(content)
    if name.endswith(".xls"):
        raise Unsupported("Old .xls files are not read yet; save the file as .xlsx")
    if name.endswith(".pdf"):
        return _pdf(content)
    if name.endswith(IMAGES):
        raise Scan("Scans need OCR, which is not set up here", is_pdf=False)
    raise Unsupported(f"Unknown file type: {file_name}")


def _decode(content: bytes) -> str:
    for encoding in ("utf-8-sig", "cp1251"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("latin-1")


def _csv(content: bytes) -> Table:
    text = _decode(content)
    try:
        dialect = csv.Sniffer().sniff(text[:4096], delimiters=";,\t|")
    except csv.Error:
        dialect = csv.excel
    return _table(list(csv.reader(io.StringIO(text), dialect)))


def _xlsx(content: bytes) -> Table:
    from openpyxl import load_workbook

    try:
        book = load_workbook(io.BytesIO(content), read_only=True, data_only=True)
    except Exception as e:  # openpyxl raises zipfile, KeyError and its own errors for broken files
        raise Unsupported(f"Not a readable .xlsx file: {e}") from e
    try:
        return _table([list(r) for r in book.worksheets[0].iter_rows(values_only=True)])
    finally:
        book.close()


def _parquet(content: bytes) -> Table:
    """A Parquet file, as AWS and Azure write FOCUS exports. Decimals, dates and timestamps come typed, as from
    Excel; a missing value is None."""
    import pyarrow as pa
    import pyarrow.parquet as pq

    try:
        table = pq.read_table(pa.BufferReader(content))
    except (pa.ArrowException, OSError) as e:
        raise Unsupported(f"Not a readable Parquet file: {e}") from e
    columns = table.column_names
    data = [table.column(c).to_pylist() for c in columns]
    return _table([columns, *[list(row) for row in zip(*data, strict=True)]])


def _pdf(content: bytes) -> Table:
    """Tables of a PDF with a text layer. The header row is the first row of the first table; later tables with
    the same header (an invoice continued on the next page) add their rows."""
    import pdfplumber

    try:
        pdf = pdfplumber.open(io.BytesIO(content))
    except Exception as e:  # pdfminer raises its own errors for broken files
        raise Unsupported(f"Not a readable PDF file: {e}") from e
    lines: list[list] = []
    with pdf:
        texts: list[str] = []
        for page in pdf.pages:
            texts.append(page.extract_text() or "")
            for found in page.extract_tables():
                found = [[None if c is None else " ".join(str(c).split()) for c in r] for r in found]
                if lines and found and found[0] == lines[0]:
                    found = found[1:]
                lines.extend(found)
    if not lines:
        text = "".join(texts).strip()
        if not text:
            raise Scan("The PDF has no text layer, it is a scan; scans need OCR, which is not set up here",
                       is_pdf=True)
        raise TextOnly("No table found in the PDF", text)
    return _table(lines)


def _blank(line: list) -> bool:
    return all(v is None or str(v).strip() == "" for v in line)


def _table(lines: list[list]) -> Table:
    start = next((i for i, line in enumerate(lines) if not _blank(line)), None)
    if start is None:
        return Table([], [])
    columns: list[str] = []
    for i, cell in enumerate(lines[start]):
        name = ("" if cell is None else str(cell).strip()) or f"column {i + 1}"
        while name in columns:
            name += "'"
        columns.append(name)
    rows = []
    for number, line in enumerate(lines[start + 1:], start=start + 2):
        if not _blank(line):
            rows.append((number, {c: (line[i] if i < len(line) else None) for i, c in enumerate(columns)}))
    return Table(columns, rows)
