import logging

import pytest
from confluent_kafka import KafkaError, KafkaException

from costguard_common import consuming

log = logging.getLogger("test")


@pytest.mark.parametrize("code", [KafkaError.UNKNOWN_TOPIC_OR_PART, KafkaError._UNKNOWN_TOPIC])
def test_a_topic_that_is_not_there_yet_is_waited_for(code):
    assert code in consuming.UNKNOWN_TOPIC_CODES
    assert consuming.skip(KafkaError(code), log) is True


def test_any_other_error_stops_the_service():
    with pytest.raises(KafkaException):
        consuming.skip(KafkaError(KafkaError._TRANSPORT), log)
