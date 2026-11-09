package ai.costguard.core.shared.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends again what Kafka or the Schema Registry refused. Without it an incomplete publication waited for the next
 * restart: an agent created during a Kafka hiccup stayed unknown to the gateway until someone restarted the core.
 * Only publications older than the delay are taken, so one still being sent is not sent twice at once; a repeat
 * is harmless anyway, consumers dedupe by key.
 */
@Component
@ConditionalOnProperty(name = "costguard.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRetry {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetry.class);

    private final IncompleteEventPublications incomplete;
    private final Duration olderThan;
    private final Counter failures;

    OutboxRetry(IncompleteEventPublications incomplete,
            @Value("${costguard.outbox.retry-after:PT1M}") Duration olderThan, MeterRegistry registry) {
        this.incomplete = incomplete;
        this.olderThan = olderThan;
        this.failures = Counter.builder("costguard.outbox.retry.failures")
                .description("Rounds of the outbox retry that failed (ADR 0026)").register(registry);
    }

    @Scheduled(fixedDelayString = "${costguard.outbox.retry-after:PT1M}",
            initialDelayString = "${costguard.outbox.retry-after:PT1M}")
    void retry() {
        try {
            incomplete.resubmitIncompletePublicationsOlderThan(olderThan);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Outbox retry failed, next round tries again: {}", e.getMessage());
        }
    }
}
