package ai.costguard.core.journal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationLogTest {

    private static final UUID TENANT = UUID.fromString("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a");
    private static final UUID AGENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void readsOneCompanyNewestFirst() {
        String sql = OperationLog.query(TENANT, null, null, 200);
        assertThat(sql).contains("FROM agent_operations FINAL")
                .contains("WHERE tenant_id = toUUID('" + TENANT + "')")
                .endsWith("ORDER BY occurred_at DESC LIMIT 200")
                .doesNotContain("agent_id = ").doesNotContain("occurred_at <");
    }

    @Test
    void filtersByAgentAndPagesBackByTime() {
        String sql = OperationLog.query(TENANT, AGENT, Instant.parse("2026-11-10T10:15:30.123Z"), 50);
        assertThat(sql).contains("AND agent_id = toUUID('" + AGENT + "')")
                .contains("AND occurred_at < toDateTime64('2026-11-10 10:15:30.123', 3, 'UTC')")
                .endsWith("LIMIT 50");
    }
}
