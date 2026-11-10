package ai.costguard.core.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.ledger.BudgetLimitChanged;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** The ledger sends what every budget was charged, so the gateway can restore counters lost with Redis (ADR 0040). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CounterSnapshotIntegrationTest.Seen.class)
class CounterSnapshotIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Seen {
        final List<BudgetLimitChanged> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(BudgetLimitChanged e) {
            events.add(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired OperationPosting posting;
    @Autowired CounterSnapshot snapshot;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired Seen seen;

    final UUID tenant = UUID.randomUUID();
    final UUID agent = UUID.randomUUID();

    RequestPostProcessor finance() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of("finance")))).authorities(realmRoles);
    }

    String budget(String level, UUID ref, String parent) throws Exception {
        String response = mvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).with(finance())
                        .content("""
                                {"name":"B","level":"%s","refId":%s,"parentId":%s,"limit":1000,"currency":"RUB"}"""
                                .formatted(level, ref == null ? "null" : '"' + ref.toString() + '"',
                                        parent == null ? "null" : '"' + parent + '"')))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    List<BudgetLimitChanged> snapshotOf(Instant asOf) {
        seen.events.clear();
        tx.executeWithoutResult(s -> snapshot.snapshot(tenant, asOf));
        return seen.events.stream().filter(e -> e.tenantId().equals(tenant)).toList();
    }

    @Test
    void everyBudgetGoesOutWithWhatTheLedgerChargedBeforeTheMoment() throws Exception {
        String company = budget("company", null, null);
        String department = budget("department", UUID.randomUUID(), company);
        String agentBudget = budget("agent", agent, department);
        Instant t = Instant.parse("2026-11-10T12:00:00Z");
        posting.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("100"), t.minusSeconds(600));
        UUID reversed = UUID.randomUUID();
        posting.charge(tenant, reversed, agent, new BigDecimal("40"), t.minusSeconds(300));
        posting.reverse(tenant, reversed, t.minusSeconds(200));
        posting.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("7"), t.plusSeconds(1)); // after the moment

        List<BudgetLimitChanged> sent = snapshotOf(t);

        assertThat(sent).hasSize(3).allSatisfy(e -> {
            assertThat(e.ledgerAsOf()).isEqualTo(t);
            assertThat(e.ledgerSpent()).isEqualByComparingTo("100");
            assertThat(e.limitAmount()).isEqualByComparingTo("1000");
            assertThat(e.version()).isEqualTo(1);
        });
        assertThat(sent).extracting(e -> e.budgetId().toString()).containsExactlyInAnyOrder(company, department, agentBudget);
        // a budget nothing was charged to goes out with zero
        String idle = budget("agent", UUID.randomUUID(), department);
        assertThat(snapshotOf(t)).filteredOn(e -> e.budgetId().toString().equals(idle)).singleElement()
                .satisfies(e -> assertThat(e.ledgerSpent()).isEqualByComparingTo("0"));
    }

    @Test
    void theJobVisitsEveryCompanyWithBudgets() throws Exception {
        budget("company", null, null);
        assertThat(jdbc.sql("select count(*) from ledger_tenant where tenant_id = ?").param(tenant).query(Long.class)
                .single()).isEqualTo(1);
        seen.events.clear();
        snapshot.everyTenant();
        assertThat(seen.events).anyMatch(e -> e.tenantId().equals(tenant) && e.ledgerAsOf() != null);
    }

    @Test
    void aChangeOfTheLimitCarriesNoSnapshot() throws Exception {
        seen.events.clear();
        budget("company", null, null);
        assertThat(seen.events).filteredOn(e -> e.tenantId().equals(tenant)).singleElement()
                .satisfies(e -> assertThat(e.ledgerSpent()).isNull());
    }
}
