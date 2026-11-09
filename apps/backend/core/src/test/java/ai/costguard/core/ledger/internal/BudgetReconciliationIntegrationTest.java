package ai.costguard.core.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.core.privacy.internal.PrivacyTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** ClickHouse is the fake of {@link PrivacyTestSupport}: it answers the reconciliation query with CLICKHOUSE_ROWS. */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetReconciliationIntegrationTest extends PrivacyTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired OperationPosting ledger;
    @Autowired BudgetReconciliation reconciliation;
    @Autowired MeterRegistry registry;

    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    private RequestPostProcessor finance() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of("finance")))).authorities(realmRoles);
    }

    private String budget(String level, UUID ref, String parent) throws Exception {
        String body = """
                {"name":"B","level":"%s","refId":%s,"parentId":%s,"limit":100000,"currency":"RUB"}
                """.formatted(level, ref == null ? "null" : '"' + ref.toString() + '"',
                parent == null ? "null" : '"' + parent + '"');
        return JsonPath.read(mvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(finance())).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void theGatewayAndTheLedgerAgreeOrTheGaugeSaysHowManyDoNot() throws Exception {
        String company = budget("company", null, null);
        String department = budget("department", UUID.randomUUID(), company);
        budget("agent", agent, department);
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        assertThat(ledger.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("100.50"), twoHoursAgo)).isTrue();
        assertThat(ledger.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("20"), twoHoursAgo)).isTrue();
        // Inside the last hour: not settled yet, the round leaves it out on both sides.
        ledger.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("999"), Instant.now().minusSeconds(60));

        CLICKHOUSE_ROWS.set(tenant + "\tRUB\t120.500000\n");
        assertThat(reconciliation.reconcile()).isEmpty();
        assertThat(gauge("costguard.budget.reconciliation.mismatches")).isZero();
        assertThat(gauge("costguard.budget.reconciliation.last.success.timestamp.seconds")).isPositive();

        // The gateway charged more than the ledger holds, and another company spent with no budget at all.
        UUID withoutBudget = UUID.randomUUID();
        CLICKHOUSE_ROWS.set(tenant + "\tRUB\t170.500000\n" + withoutBudget + "\tRUB\t10.000000\n");
        List<BudgetReconciliation.Mismatch> found = reconciliation.reconcile();
        assertThat(found).extracting(BudgetReconciliation.Mismatch::tenantId).containsExactlyInAnyOrder(tenant,
                withoutBudget);
        assertThat(found).filteredOn(m -> m.tenantId().equals(tenant)).singleElement()
                .satisfies(m -> assertThat(m.difference()).isEqualByComparingTo("50"));
        assertThat(gauge("costguard.budget.reconciliation.mismatches")).isEqualTo(2);
        assertThat(gauge("costguard.budget.reconciliation.max.difference")).isEqualTo(50.0);
        CLICKHOUSE_ROWS.set("");
    }
}
