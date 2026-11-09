package ai.costguard.core.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How far behind the outbox is (ADR 0026): events stored with a change and not yet delivered. The table has no RLS,
 * it belongs to Spring Modulith. The query is cached for 30 seconds so a scrape every few seconds does not become a
 * query every few seconds.
 *
 * <ul>
 *   <li>{@code costguard_outbox_pending_events} — undelivered publications;</li>
 *   <li>{@code costguard_outbox_oldest_pending_seconds} — age of the oldest, 0 when none.</li>
 * </ul>
 */
@Component
class OutboxMetrics implements MeterBinder {

    private static final Duration CACHE = Duration.ofSeconds(30);

    private record Snapshot(Instant at, double pending, double oldestSeconds) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Snapshot last = new Snapshot(Instant.EPOCH, 0, 0);

    OutboxMetrics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Snapshot snapshot() {
        Snapshot s = last;
        Instant now = clock.instant();
        if (s.at().plus(CACHE).isAfter(now)) {
            return s;
        }
        try {
            s = jdbc.sql("""
                    select count(*) as pending,
                           coalesce(extract(epoch from (now() - min(publication_date))), 0) as oldest
                    from event_publication where completion_date is null
                    """).query((rs, n) -> new Snapshot(now, rs.getLong("pending"), rs.getDouble("oldest"))).single();
        } catch (RuntimeException e) {
            // A gauge that throws breaks the whole scrape; NaN says "unknown" for this one only.
            s = new Snapshot(now, Double.NaN, Double.NaN);
        }
        last = s;
        return s;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("costguard.outbox.pending.events", this, m -> m.snapshot().pending())
                .description("Events stored with a change and not delivered yet").register(registry);
        Gauge.builder("costguard.outbox.oldest.pending.seconds", this, m -> m.snapshot().oldestSeconds())
                .description("Age of the oldest undelivered event").baseUnit("seconds").register(registry);
    }
}
