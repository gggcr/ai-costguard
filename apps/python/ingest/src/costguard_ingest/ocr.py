"""OCR of scans and photos with Tesseract (ADR 0012).

Tesseract runs as a program (`tesseract stdin stdout -l rus+eng`), so the Python side needs nothing new: pages of
a PDF are rendered with pypdfium2 and photos are normalized with Pillow, both already there through pdfplumber.
When the program or its language data are missing, OcrUnavailable says so in plain words instead of a crash.
"""

from __future__ import annotations

import io
import shutil
import subprocess
from typing import Protocol

LANGUAGES = "rus+eng"
DPI = 300
MAX_PAGES = 20


class OcrUnavailable(Exception):
    """OCR cannot run here: the message says what to install or set."""


class Ocr(Protocol):
    def text(self, image: bytes) -> str:
        """The text of one image (PNG, JPEG or TIFF bytes)."""


class Tesseract:
    def __init__(self, binary: str = "tesseract", languages: str = LANGUAGES, timeout: float = 120) -> None:
        self.binary, self.languages, self.timeout = binary, languages, timeout

    def available(self) -> bool:
        return shutil.which(self.binary) is not None

    def text(self, image: bytes) -> str:
        if not self.available():
            raise OcrUnavailable(f"Scans need OCR, and Tesseract ({self.binary}) is not installed here: install "
                                 "tesseract-ocr with the rus and eng language data, or set INGEST_TESSERACT")
        try:
            done = subprocess.run([self.binary, "stdin", "stdout", "-l", self.languages], input=image,
                                  capture_output=True, timeout=self.timeout, check=False)
        except subprocess.TimeoutExpired as e:
            raise OcrUnavailable(f"OCR took longer than {self.timeout:.0f} s; the scan may be too large") from e
        error = done.stderr.decode("utf-8", "replace")
        if done.returncode != 0:
            if "Failed loading language" in error or "Error opening data file" in error:
                raise OcrUnavailable(f"Tesseract has no language data for {self.languages}: install "
                                     "tesseract-ocr-rus and tesseract-ocr-eng")
            raise OcrUnavailable(f"Tesseract could not read the image: {error.strip()[:200]}")
        return done.stdout.decode("utf-8", "replace")


def pdf_pages(content: bytes, dpi: int = DPI, limit: int = MAX_PAGES) -> list[bytes]:
    """Each page of a PDF as PNG, at most `limit` pages."""
    import pypdfium2

    pdf = pypdfium2.PdfDocument(content)
    try:
        out = []
        for index in range(min(len(pdf), limit)):
            bitmap = pdf[index].render(scale=dpi / 72)
            buffer = io.BytesIO()
            bitmap.to_pil().save(buffer, format="PNG")
            out.append(buffer.getvalue())
        return out
    finally:
        pdf.close()


def image(content: bytes) -> bytes:
    """A photo as PNG, turned upright by its EXIF orientation: phones store the picture sideways."""
    from PIL import Image, ImageOps, UnidentifiedImageError

    try:
        picture = Image.open(io.BytesIO(content))
        picture = ImageOps.exif_transpose(picture).convert("RGB")
    except (UnidentifiedImageError, OSError) as e:
        raise OcrUnavailable(f"The image cannot be opened ({e}); HEIC photos need converting to JPEG first") from e
    buffer = io.BytesIO()
    picture.save(buffer, format="PNG")
    return buffer.getvalue()


def read(ocr: Ocr, content: bytes, is_pdf: bool) -> str:
    """The text of a scanned PDF or a photo, pages separated by a form feed."""
    pages = pdf_pages(content) if is_pdf else [image(content)]
    return "\f".join(ocr.text(page) for page in pages).strip()
