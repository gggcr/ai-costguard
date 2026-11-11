"""Traces of the Python services (ADR 0050): the same OpenTelemetry Collector and Tempo as the core and the gateway.

Off unless COSTGUARD_TRACING is true and OTEL_EXPORTER_OTLP_TRACES_ENDPOINT is set (the Helm chart sets both with
tracing.enabled). Off, the OpenTelemetry API is a no-op, so the calls below cost nothing.

A trace crosses Kafka in the W3C `traceparent` header: the core and the gateway put it on what they publish, a service
continues it in `consumed` and passes it on in `headers`. So an upload is one trace from the HTTP request to the core
through ingest.files.v1, the pipeline and the cost records it writes.

Spans carry topics, partitions and offsets, never message contents: personal data stays out (ADR 0023).
"""

from __future__ import annotations

import contextlib
import functools
import logging
import os
from collections.abc import Iterator

from opentelemetry import context, propagate, trace
from opentelemetry.trace import SpanKind

log = logging.getLogger("costguard.tracing")
TRACER = "costguard"

_enabled = False


def enabled() -> bool:
    return _enabled


def wanted(env=os.environ) -> bool:
    return env.get("COSTGUARD_TRACING", "").lower() in ("1", "true", "yes") and bool(
        env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))


def setup(service: str, env=os.environ, exporter=None) -> bool:
    """Starts exporting spans of this process as `service`; True when tracing is on. `exporter` is for tests."""
    global _enabled
    if _enabled or not (exporter or wanted(env)):
        return _enabled
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor

    provider = TracerProvider(resource=Resource.create({"service.name": service}))
    if exporter is None:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    else:
        provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    try:  # calls to the core, NEAR, S3 and the gateway become child spans and carry traceparent
        from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

        HTTPXClientInstrumentor().instrument()
    except Exception:  # a missing or broken instrumentation must not stop the service
        log.exception("httpx is not traced")
    _enabled = True
    log.info("Traces of %s go to %s", service, env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "a test exporter"))
    return True


def shutdown() -> None:
    provider = trace.get_tracer_provider()
    if hasattr(provider, "shutdown"):
        provider.shutdown()


def _carrier(headers) -> dict[str, str]:
    out: dict[str, str] = {}
    for key, value in headers or []:
        if value is not None:
            out[key] = value.decode("latin-1") if isinstance(value, bytes) else str(value)
    return out


@contextlib.contextmanager
def consumed(msg, name: str | None = None) -> Iterator[trace.Span]:
    """A span for handling one Kafka message, continuing the trace of whoever published it."""
    parent = propagate.extract(_carrier(msg.headers()))
    token = context.attach(parent)
    try:
        with trace.get_tracer(TRACER).start_as_current_span(
                name or f"{msg.topic()} process", kind=SpanKind.CONSUMER,
                attributes={"messaging.system": "kafka", "messaging.operation": "process",
                            "messaging.destination.name": msg.topic(),
                            "messaging.kafka.destination.partition": msg.partition(),
                            "messaging.kafka.message.offset": msg.offset()}) as span:
            yield span
    finally:
        context.detach(token)


@contextlib.contextmanager
def span(name: str, **attributes) -> Iterator[trace.Span]:
    """A span of a job or a step: a run of the connector agent, a forecast, a report."""
    with trace.get_tracer(TRACER).start_as_current_span(name, attributes=attributes) as s:
        yield s


def headers() -> list[tuple[str, bytes]]:
    """Kafka headers that carry the current trace on to the consumers of a message."""
    carrier: dict[str, str] = {}
    propagate.inject(carrier)
    return [(k, v.encode("latin-1")) for k, v in carrier.items()]


def job(service: str, name: str):
    """Decorates the main of a job (cron, Airflow): tracing on for the process and one span for the whole run."""
    def wrap(main):
        @functools.wraps(main)
        def run(*args, **kwargs):
            setup(service)
            try:
                with span(name):
                    return main(*args, **kwargs)
            finally:
                if _enabled:
                    shutdown()
        return run
    return wrap
