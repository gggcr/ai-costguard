package ai.costguard.core.shared.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The core of an install without Kafka does not start with a guessable internal token (ADR 0080). */
class InternalTokenTest {

    @Test
    void aShortOrMissingTokenStopsTheStart() {
        assertThatThrownBy(() -> new InternalSecurityConfig().internal(null, ""))
                .hasMessageContaining("INTERNAL_TOKEN");
        assertThatThrownBy(() -> new InternalSecurityConfig().internal(null, "x".repeat(31)))
                .hasMessageContaining("at least 32");
    }
}
