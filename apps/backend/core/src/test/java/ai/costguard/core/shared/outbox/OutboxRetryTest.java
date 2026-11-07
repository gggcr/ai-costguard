package ai.costguard.core.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

class OutboxRetryTest {

    final List<Duration> calls = new ArrayList<>();

    final IncompleteEventPublications incomplete = new IncompleteEventPublications() {
        @Override
        public void resubmitIncompletePublications(Predicate<EventPublication> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resubmitIncompletePublications(org.springframework.modulith.events.ResubmissionOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resubmitIncompletePublicationsOlderThan(Duration duration) {
            calls.add(duration);
            if (calls.size() == 1) {
                throw new IllegalStateException("Schema Registry is not reachable");
            }
        }
    };

    @Test
    void resubmitsOnEveryRoundAndSurvivesAFailedOne() {
        OutboxRetry retry = new OutboxRetry(incomplete, Duration.ofMinutes(1));
        retry.retry();
        retry.retry();
        assertThat(calls).containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(1));
    }
}
