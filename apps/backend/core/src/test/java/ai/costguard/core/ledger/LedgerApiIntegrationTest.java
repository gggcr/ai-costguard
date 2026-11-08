package ai.costguard.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(LedgerApiIntegrationTest.Limits.class)
class LedgerApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Limits {
        final List<BudgetLimitChanged> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(BudgetLimitChanged event) {
            seen.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired OperationPosting posting;
    @Autowired Limits limits;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");
    private String company;
    private String department;
    private String agentBudget;

    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor finance() {
        return as(tenant, "finance");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String level, UUID ref, String parent) {
        return """
                {"name":"B","level":"%s","refId":%s,"parentId":%s,"limit":1000,"currency":"RUB"}
                """.formatted(level, ref == null ? "null" : '"' + ref.toString() + '"',
                parent == null ? "null" : '"' + parent + '"');
    }

    private String budget(String level, UUID ref, String parent) throws Exception {
        String response = mvc.perform(json(post("/api/v1/budgets"), body(level, ref, parent)).with(finance()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @BeforeEach
    void tree() throws Exception {
        company = budget("company", null, null);
        department = budget("department", UUID.randomUUID(), company);
        agentBudget = budget("agent", agent, department);
    }

    private void expectSpent(String budgetId, double amount) throws Exception {
        mvc.perform(get("/api/v1/budgets/" + budgetId).with(finance())).andExpect(jsonPath("$.spent").value(amount));
    }

    @Test
    void aChargeReachesEveryLevel() throws Exception {
        assertThat(posting.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("12.50"), now)).isTrue();

        expectSpent(agentBudget, 12.5);
        expectSpent(department, 12.5);
        expectSpent(company, 12.5);
    }

    @Test
    void aRedeliveredOperationIsChargedOnce() throws Exception {
        UUID operation = UUID.randomUUID();

        posting.charge(tenant, operation, agent, new BigDecimal("10"), now);
        posting.charge(tenant, operation, agent, new BigDecimal("10"), now);

        expectSpent(company, 10);
    }

    @Test
    void aReversalCancelsTheChargeOnce() throws Exception {
        UUID operation = UUID.randomUUID();
        posting.charge(tenant, operation, agent, new BigDecimal("10"), now);

        posting.reverse(tenant, operation, now);
        posting.reverse(tenant, operation, now);

        expectSpent(agentBudget, 0);
        expectSpent(company, 0);
        mvc.perform(get("/api/v1/budgets/" + company + "/entries").with(finance()))
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void anAgentWithoutABudgetIsReported() {
        assertThat(posting.charge(tenant, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, now)).isFalse();
    }

    @Test
    void theLedgerCannotBeRewritten() {
        posting.charge(tenant, UUID.randomUUID(), agent, BigDecimal.ONE, now);

        for (String sql : List.of("update ledger_entry set amount = 0", "delete from ledger_entry")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenant.toString())
                        .query(String.class).single();
                jdbc.sql(sql).update();
            })).hasMessageContaining("append-only");
        }
    }

    @Test
    void aLimitChangeReachesTheGatewayWithANewVersion() throws Exception {
        mvc.perform(json(put("/api/v1/budgets/" + agentBudget + "/limit"), "{\"limit\":5}").with(finance()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.version").value(2));

        UUID id = UUID.fromString(agentBudget);
        BudgetLimitChanged last = limits.seen.stream().filter(e -> e.budgetId().equals(id)).reduce((a, b) -> b)
                .orElseThrow();
        assertThat(last.version()).isEqualTo(2);
        assertThat(last.limitAmount()).isEqualByComparingTo("5");
        assertThat(last.parentBudgetId()).isEqualTo(UUID.fromString(department));
    }

    @Test
    void aCompanyHasOneBudget() throws Exception {
        mvc.perform(json(post("/api/v1/budgets"), body("company", null, null)).with(finance()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("budget_exists"));
    }

    @Test
    void anAgentHasOneBudget() throws Exception {
        mvc.perform(json(post("/api/v1/budgets"), body("agent", agent, department)).with(finance()))
                .andExpect(status().isConflict());
    }

    @Test
    void levelsMustNest() throws Exception {
        mvc.perform(json(post("/api/v1/budgets"), body("agent", UUID.randomUUID(), company)).with(finance()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_hierarchy"));
        mvc.perform(json(post("/api/v1/budgets"), body("department", UUID.randomUUID(), null)).with(finance()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aParentOfAnotherTenantIsNotFound() throws Exception {
        mvc.perform(json(post("/api/v1/budgets"), body("department", UUID.randomUUID(), company))
                        .with(as(UUID.randomUUID(), "owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("parent_not_found"));
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        UUID other = UUID.randomUUID();

        mvc.perform(get("/api/v1/budgets").with(as(other, "owner"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/budgets/" + company).with(as(other, "owner"))).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/budgets/" + company + "/limit"), "{\"limit\":1}").with(as(other, "owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void itAdminCanReadButNotChange() throws Exception {
        mvc.perform(get("/api/v1/budgets").with(as(tenant, "it_admin"))).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/budgets/" + company + "/limit"), "{\"limit\":1}").with(as(tenant, "it_admin")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
    }
}
