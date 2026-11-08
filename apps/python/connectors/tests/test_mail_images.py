"""Photos and scans from the mailbox go to OCR; the logos of signatures do not."""

import io
import os
from email.message import EmailMessage

import httpx
from PIL import Image

from costguard_connectors import mail


def picture(width: int, height: int, fmt: str) -> bytes:
    """Noise compresses badly, so the size in bytes is that of a real photo."""
    out = io.BytesIO()
    Image.frombytes("RGB", (width, height), os.urandom(width * height * 3)).save(out, format=fmt)
    return out.getvalue()


def letter(*files: tuple[str, bytes, str], inline: bool = False) -> bytes:
    m = EmailMessage()
    m["Subject"] = "Счёт"
    m.set_content("Фото счёта во вложении")
    m.add_alternative("<p>Счёт</p><img src='cid:logo'>", subtype="html")
    for name, content, subtype in files:
        m.add_attachment(content, maintype="image", subtype=subtype, filename=name,
                         disposition="inline" if inline else "attachment", cid=f"<{name}>")
    return m.as_bytes()


def names(raw: bytes, **kw) -> list[str]:
    return [a.file_name for a in mail.attachments(raw, **kw)]


def test_a_photo_of_an_invoice_is_taken_and_a_logo_is_not():
    raw = letter(("IMG_0412.jpg", picture(1200, 1600, "JPEG"), "jpeg"), ("logo.png", picture(180, 60, "PNG"), "png"))
    assert names(raw) == ["IMG_0412.jpg"]


def test_a_heavy_image_with_a_short_side_is_a_banner_not_a_document():
    assert names(letter(("banner.png", picture(1400, 200, "PNG"), "png"))) == []


def test_an_inline_photo_from_a_phone_is_still_taken():
    assert names(letter(("IMG_0413.jpeg", picture(1000, 1400, "JPEG"), "jpeg"), inline=True)) == ["IMG_0413.jpeg"]


def test_formats_without_a_readable_header_go_by_bytes():
    assert names(letter(("scan.tif", picture(800, 1100, "TIFF"), "tiff"))) == ["scan.tif"]
    assert names(letter(("tiny.webp", b"RIFF" + b"\0" * 100, "webp"))) == []
    assert names(letter(("anim.gif", picture(800, 800, "GIF"), "gif"))) == []


def test_thresholds_can_be_changed():
    raw = letter(("logo.png", picture(180, 60, "PNG"), "png"))
    assert names(raw, min_image_kb=1, min_image_side=50) == ["logo.png"]


def test_image_size_from_png_and_jpeg_headers():
    assert mail.image_size(picture(640, 480, "PNG")) == (640, 480)
    assert mail.image_size(picture(321, 123, "JPEG")) == (321, 123)
    assert mail.image_size(b"not an image") is None
    assert mail.image_size(b"\xff\xd8\x00\x00") is None


def test_the_photo_is_uploaded_to_the_core():
    seen = []

    def core(request):
        seen.append(request)
        return httpx.Response(201, json={})

    class Box:
        def __init__(self):
            self.done = []

        def unseen(self):
            return [("1", letter(("IMG_0412.jpg", picture(1200, 1600, "JPEG"), "jpeg"),
                                 ("logo.png", picture(180, 60, "PNG"), "png")))]

        def mark_seen(self, uid):
            self.done.append(uid)

    box = Box()
    client = httpx.Client(base_url="http://core", transport=httpx.MockTransport(core))
    assert mail.MailInvoices(box, client).collect() == 1
    assert box.done == ["1"] and b"IMG_0412.jpg" in seen[0].content
