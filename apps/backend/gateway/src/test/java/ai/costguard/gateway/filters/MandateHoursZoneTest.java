package ai.costguard.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.AgentState;
import ai.costguard.events.OperationKind;
import ai.costguard.gateway.ladder.Facts;
import ai.costguard.gateway.ladder.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0073: 9:00-18:00 of a mandate are the company's hours, not the gateway's. */
class MandateHoursZoneTest {

    private static final ZoneId CONFIGURED = ZoneId.of("Europe/Moscow");
    private final MandateFilter filter = new MandateFilter(null, CONFIGURED);

    /** 2026-11-23 15:00 UTC: 18:00 in Moscow, 07:00 in Los Angeles. */
    private static Operation op(String zone) {
        Operation op = new Operation(UUID.randomUUID(), "k", null, OperationKind.LLM_CALL, "OpenAI", "gpt-5",
                new BigDecimal("1.000000"), Instant.parse("2026-11-23T15:00:00Z"));
        op.agent(AgentState.newBuilder().setTenantId(UUID.randomUUID()).setAgentId(UUID.randomUUID())
                .setVirtualKeyHashes(List.of()).setSuspended(false).setUpdatedAt(Instant.now()).setTimeZone(zone)
                .build());
        return op;
    }

    @Test
    void theHoursAreTheCompanys() {
        Operation op = op("America/Los_Angeles");
        assertThat(filter.zoneOf(op)).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(Facts.of(op, filter.zoneOf(op)).time()).isEqualTo("07:00");
    }

    @Test
    void anOlderCoreLeavesTheConfiguredZone() {
        assertThat(filter.zoneOf(op(null))).isEqualTo(CONFIGURED);
        assertThat(filter.zoneOf(op("+03:00"))).isEqualTo(CONFIGURED);
        assertThat(filter.zoneOf(op("Mars/Olympus"))).isEqualTo(CONFIGURED);
        assertThat(Facts.of(op(null), CONFIGURED).time()).isEqualTo("18:00");
    }
}
