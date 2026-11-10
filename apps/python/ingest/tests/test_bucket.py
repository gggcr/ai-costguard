import gzip
import json
from datetime import UTC, datetime, timedelta

import httpx

from costguard_ingest import bucket

NOW = datetime(2026, 11, 10, 12, tzinfo=UTC)
FOCUS = (b"BilledCost,BillingCurrency,ChargePeriodStart,ChargePeriodEnd,ProviderName,ServiceName\n"
         b"120.50,RUB,2026-11-01,2026-11-02,Yandex Cloud,Compute Cloud\n")


class FakeBucket:
    def __init__(self, objects: dict[str, tuple[bytes, str, datetime]]):
        self.objects_ = objects
        self.reads: list[str] = []

    def objects(self, prefix):
        for key, (content, etag, modified) in self.objects_.items():
            if key.startswith(prefix):
                yield bucket.Object(key, etag, len(content), modified)

    def read(self, key):
        self.reads.append(key)
        return self.objects_[key][0]


class FakeCore:
    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.fail = False

    def handler(self, request: httpx.Request) -> httpx.Response:
        if self.fail:
            return httpx.Response(503)
        body = request.content
        name = body.split(b'filename="', 1)[1].split(b'"', 1)[0].decode()
        content = body.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n--", 1)[0]
        known = content in self.files.values()
        self.files.setdefault(name, content)
        return httpx.Response(200 if known else 201, json={"id": f"imp-{len(self.files)}", "fileName": name})

    def client(self):
        return httpx.Client(base_url="http://core", transport=httpx.MockTransport(self.handler))


def test_new_exports_go_up_once_and_a_changed_one_again(tmp_path):
    yesterday = NOW - timedelta(days=1)
    store = FakeBucket({
        "focus/2026-11/part-1.csv.gz": (gzip.compress(FOCUS), "e1", yesterday),
        "focus/2026-11/manifest.json": (b"{}", "m1", yesterday),
        "focus/2026-11/part-2.parquet": (b"PAR1", "p1", yesterday),
        "focus/2025-01/old.csv": (FOCUS, "o1", NOW - timedelta(days=300)),
        "other/x.csv": (FOCUS, "x1", yesterday),
    })
    core = FakeCore()
    path = tmp_path / "state.json"
    state = bucket.State.load(path)
    s = bucket.pull(store, core.client(), "focus/", state, NOW - timedelta(days=7))
    state.save(path)
    assert s.uploaded == ["focus/2026-11/part-1.csv.gz"]
    assert core.files == {"part-1.csv": FOCUS}  # unpacked, named without .gz
    assert s.skipped == [("focus/2026-11/part-2.parquet", "Parquet is not read: export FOCUS as CSV")]
    assert s.imports == {"focus/2026-11/part-1.csv.gz": "imp-1"}

    # the next run reads nothing it has seen
    store.reads.clear()
    state = bucket.State.load(path)
    again = bucket.pull(store, core.client(), "focus/", state, NOW - timedelta(days=7))
    assert store.reads == [] and again.uploaded == [] and again.skipped == []

    # the provider rewrote the file: a new ETag is read again; the same bytes are known to the core
    store.objects_["focus/2026-11/part-1.csv.gz"] = (gzip.compress(FOCUS), "e2", NOW)
    rewritten = bucket.pull(store, core.client(), "focus/", state, NOW - timedelta(days=7))
    assert rewritten.known == ["focus/2026-11/part-1.csv.gz"] and rewritten.uploaded == []


def test_a_core_that_does_not_answer_leaves_the_file_for_the_next_run(tmp_path):
    store = FakeBucket({"focus/a.csv": (FOCUS, "e1", NOW)})
    core = FakeCore()
    core.fail = True
    state = bucket.State()
    s = bucket.pull(store, core.client(), "", state, NOW - timedelta(days=1))
    assert s.failed and s.failed[0][0] == "focus/a.csv" and state.seen == {}
    core.fail = False
    assert bucket.pull(store, core.client(), "", state, NOW - timedelta(days=1)).uploaded == ["focus/a.csv"]


def test_a_broken_archive_is_reported_and_not_read_again():
    store = FakeBucket({"a.csv.gz": (b"not gzip", "e1", NOW)})
    state = bucket.State()
    s = bucket.pull(store, FakeCore().client(), "", state, NOW - timedelta(days=1))
    assert s.failed[0][0] == "a.csv.gz" and state.seen == {"a.csv.gz": "e1"}


def test_the_state_survives_a_broken_file(tmp_path):
    path = tmp_path / "s.json"
    path.write_text("{broken", "utf-8")
    assert bucket.State.load(path).seen == {}
    bucket.State({"k": "e"}).save(path)
    assert json.loads(path.read_text("utf-8")) == {"seen": {"k": "e"}}
    assert bucket.file_name("a/b/c.CSV.GZ") == "c.CSV" and bucket.file_name("x.xlsx") == "x.xlsx"


def test_the_s3_store_lists_pages_and_reads_objects():
    import io

    from botocore.response import StreamingBody
    from botocore.stub import Stubber

    store = bucket.S3Bucket("https://storage.yandexcloud.net", "exports", "ak", "sk")
    stub = Stubber(store._s3)
    stub.add_response("list_objects_v2", {
        "Contents": [{"Key": "focus/a.csv", "ETag": '"e1"', "Size": 3, "LastModified": NOW}],
        "IsTruncated": True, "NextContinuationToken": "t"}, {"Bucket": "exports", "Prefix": "focus/"})
    stub.add_response("list_objects_v2", {
        "Contents": [{"Key": "focus/b.csv", "ETag": '"e2"', "Size": 4, "LastModified": NOW}], "IsTruncated": False},
        {"Bucket": "exports", "Prefix": "focus/", "ContinuationToken": "t"})
    stub.add_response("get_object", {"Body": StreamingBody(io.BytesIO(b"abc"), 3)},
                      {"Bucket": "exports", "Key": "focus/a.csv"})
    with stub:
        assert [(o.key, o.etag) for o in store.objects("focus/")] == [("focus/a.csv", "e1"), ("focus/b.csv", "e2")]
        assert store.read("focus/a.csv") == b"abc"
