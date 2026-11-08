"""OCR of scans and photos, and the errors when OCR or extraction is not there."""

import io
import subprocess
import uuid

import pytest
from PIL import Image, ImageDraw

from costguard_ingest import documents, ocr, service, storage, tables


class FakeOcr:
    def __init__(self, text="Счёт № 7 от 01.11.2026\nИтого 1 200,00"):
        self.result, self.seen = text, []

    def text(self, image: bytes) -> str:
        self.seen.append(image)
        return self.result


def png(size=(200, 100)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", size, "white").save(out, format="PNG")
    return out.getvalue()


def blank_pdf(pages=2) -> bytes:
    import pypdfium2

    pdf = pypdfium2.PdfDocument.new()
    for _ in range(pages):
        pdf.new_page(200, 300)
    out = io.BytesIO()
    pdf.save(out)
    return out.getvalue()


def to_table(file_name: str, text: str, tenant=None, key=None) -> tables.Table:
    return tables.Table(["Текст"], [(2, {"Текст": text})])


def test_a_photo_is_read_by_ocr_and_its_text_extracted():
    engine = FakeOcr()
    table = documents.Reader(engine, to_table)("фото.jpg", png())
    assert table.rows[0][1]["Текст"].startswith("Счёт № 7")
    assert engine.seen[0].startswith(b"\x89PNG")


def test_every_page_of_a_scanned_pdf_is_read():
    engine = FakeOcr("страница")
    table = documents.Reader(engine, to_table)("scan.pdf", blank_pdf(pages=3))
    assert len(engine.seen) == 3
    assert table.rows[0][1]["Текст"] == "страница\fстраница\fстраница"


def test_a_pdf_with_text_but_no_table_goes_to_extraction_without_ocr():
    engine = FakeOcr()
    seen = []

    def extract(name, text, tenant=None, key=None):
        seen.append(text)
        return to_table(name, text)

    documents.Reader(engine, extract)("invoice.pdf", text_pdf("Invoice 42 Total 100.00"))
    assert "Invoice 42" in seen[0] and engine.seen == []


def test_without_ocr_a_scan_is_a_clear_error():
    with pytest.raises(tables.Unsupported, match="INGEST_OCR_ENABLED"):
        documents.Reader(None, to_table)("scan.png", png())


def test_without_extraction_ocr_text_is_a_clear_error():
    with pytest.raises(tables.Unsupported, match="LLM extraction"):
        documents.Reader(FakeOcr(), None)("scan.png", png())


def test_ocr_that_finds_nothing_says_so():
    with pytest.raises(tables.Unsupported, match="no text"):
        documents.Reader(FakeOcr("  "), to_table)("scan.png", png())


def test_tesseract_not_installed_is_a_clear_error():
    engine = ocr.Tesseract(binary="tesseract-that-is-not-there")
    assert not engine.available()
    with pytest.raises(tables.Unsupported, match="not installed"):
        documents.Reader(engine, to_table)("scan.png", png())


def test_missing_language_data_and_timeouts_are_clear_errors(monkeypatch):
    engine = ocr.Tesseract()
    monkeypatch.setattr(engine, "available", lambda: True)
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: subprocess.CompletedProcess(
        a, 1, b"", b"Failed loading language 'rus'"))
    with pytest.raises(ocr.OcrUnavailable, match="tesseract-ocr-rus"):
        engine.text(png())

    def slow(*a, **k):
        raise subprocess.TimeoutExpired("tesseract", 1)

    monkeypatch.setattr(subprocess, "run", slow)
    with pytest.raises(ocr.OcrUnavailable, match="longer than"):
        engine.text(png())


def test_a_picture_that_does_not_open_is_a_clear_error():
    with pytest.raises(tables.Unsupported, match="cannot be opened"):
        documents.Reader(FakeOcr(), to_table)("photo.heic", b"not an image")


def test_a_photo_is_turned_upright_by_exif():
    picture = Image.new("RGB", (400, 100), "white")
    exif = picture.getexif()
    exif[0x0112] = 6  # rotated 90° clockwise
    out = io.BytesIO()
    picture.save(out, format="JPEG", exif=exif)
    upright = Image.open(io.BytesIO(ocr.image(out.getvalue())))
    assert upright.size == (100, 400)


def test_the_handler_reports_missing_ocr_as_a_failed_file(tmp_path):
    (tmp_path / "t").mkdir()
    (tmp_path / "t" / "scan.png").write_bytes(png())
    handler = service.Handler(storage.LocalFiles(tmp_path),
                              documents.Reader(ocr.Tesseract(binary="tesseract-that-is-not-there"), to_table))
    file = {"file_id": uuid.uuid4(), "tenant_id": uuid.uuid4(), "s3_key": "t/scan.png", "file_name": "scan.png"}
    [result] = handler.on_file(file)
    assert result.value["status"] == "FAILED" and "Tesseract" in result.value["failure"]


def test_ocr_is_on_by_default_and_can_be_switched_off():
    assert isinstance(service.reader(service.IngestSettings()).engine, ocr.Tesseract)
    assert service.reader(service.IngestSettings(ingest_ocr_enabled=False)).engine is None


@pytest.mark.skipif(not ocr.Tesseract().available(), reason="Tesseract is not installed here")
def test_real_tesseract_reads_printed_text():
    picture = Image.new("RGB", (900, 200), "white")
    ImageDraw.Draw(picture).text((20, 60), "INVOICE 4217 TOTAL 1200", fill="black", font_size=48)
    out = io.BytesIO()
    picture.save(out, format="PNG")
    assert "4217" in ocr.read(ocr.Tesseract(languages="eng"), out.getvalue(), is_pdf=False)


def text_pdf(text: str) -> bytes:
    """A one-page PDF with one line of text and no table."""
    stream = f"BT /F1 12 Tf 50 700 Td ({text}) Tj ET".encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = io.BytesIO(), []
    out.write(b"%PDF-1.4\n")
    for i, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{i} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for o in offsets:
        out.write(f"{o:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()
