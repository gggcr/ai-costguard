"""What a Kafka consumer of a service does with an error in place of a message.

A topic that does not exist yet is not a failure: a producer creates it on its first message, and the consumer
picks it up at the next metadata refresh. On a fresh local stack `ingest.accepted.v1` appears only after the
ingest pipeline has read its first file, so stopping on it would stop the pipeline for good. In the cloud the
topics are made by Terraform (auto-create is off), and this never happens. Every other error stops the service.
"""

from __future__ import annotations

import logging

# librdkafka codes (confluent_kafka.KafkaError): the broker's UNKNOWN_TOPIC_OR_PART and the client's _UNKNOWN_TOPIC
UNKNOWN_TOPIC_CODES = frozenset({3, -188})


def skip(error, log: logging.Logger) -> bool:
    """True when the error can be passed over; raises the error as an exception otherwise."""
    if error.code() in UNKNOWN_TOPIC_CODES:
        log.warning("Waiting for a topic to appear: %s", error.str())
        return True
    from confluent_kafka import KafkaException

    raise KafkaException(error)
