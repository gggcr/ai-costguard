"""Traces of the Python services continue the trace of the message they handle and pass it on (ADR 0050)."""

import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from costguard_common import tracing

PARENT_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = f"00-{PARENT_TRACE}-00f067aa0ba902b7-01"


class Msg:
    def __init__(self, headers):
        self._headers = headers

    def headers(self):
        return self._headers

    def topic(self):
        return "ingest.files.v1"

    def partition(self):
        return 2

    def offset(self):
        return 41


@pytest.fixture(scope="module")
def spans():
    exporter = InMemorySpanExporter()
    # the global tracer provider can be set once per process, so every test shares this one
    assert tracing.setup("costguard-test", env={}, exporter=exporter)
    return exporter


@pytest.fixture(autouse=True)
def clean(spans):
    spans.clear()


def test_off_without_the_switch_and_the_endpoint():
    assert not tracing.wanted({})
    assert not tracing.wanted({"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "http://collector:4318/v1/traces"})
    assert not tracing.wanted({"COSTGUARD_TRACING": "true"})
    assert tracing.wanted({"COSTGUARD_TRACING": "true",
                           "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "http://collector:4318/v1/traces"})


def test_a_message_continues_the_trace_of_its_publisher(spans):
    with tracing.consumed(Msg([("traceparent", PARENT.encode())])):
        pass
    [span] = spans.get_finished_spans()
    assert format(span.context.trace_id, "032x") == PARENT_TRACE
    assert span.attributes["messaging.destination.name"] == "ingest.files.v1"
    assert span.attributes["messaging.kafka.message.offset"] == 41
    assert span.resource.attributes["service.name"] == "costguard-test"


def test_what_is_published_while_handling_carries_the_same_trace(spans):
    with tracing.consumed(Msg([("traceparent", PARENT.encode())])):
        out = dict(tracing.headers())
    assert out["traceparent"].decode().split("-")[1] == PARENT_TRACE


def test_a_message_without_a_trace_starts_one(spans):
    with tracing.consumed(Msg(None)):
        pass
    [span] = spans.get_finished_spans()
    assert span.parent is None


def test_spans_carry_no_message_content(spans):
    with tracing.consumed(Msg([("traceparent", PARENT.encode()), ("x-unrelated", b"secret")])):
        pass
    [span] = spans.get_finished_spans()
    assert all("secret" not in str(v) for v in span.attributes.values())


def test_a_job_is_one_span(spans):
    @tracing.job("costguard-test", "forecast")
    def main():
        with tracing.span("step"):
            return 7

    assert main() == 7
    names = {s.name for s in spans.get_finished_spans()}
    assert {"forecast", "step"} <= names
