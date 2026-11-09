package ai.costguard.core.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.core.journal.Receipts;
import ai.costguard.core.privacy.CompactedRecordsErased;
import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CompanyErasureIntegrationTest.Tombstones.class)
class CompanyErasureIntegrationTest extends PrivacyTestSupport {

    @TestConfiguration
    static class Tombstones {
        final List<CompactedRecordsErased> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(CompactedRecordsErased e) {
            seen.add(e);
        }
    }

    /** Kept on purpose when the company goes: the record that it was erased (ADR 0021). */
    private static final Set<String> KEPT = Set.of("company_erasure");

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired PrivacyService service;
    @Autowired List<DataHolder> holders;
    @Autowired Tombstones tombstones;
    @Autowired Receipts receipts;
    @Autowired ConnectorRuns runs;

    private final UUID tenant = UUID.randomUUID();
    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor as(UUID tenantId, String role) {
        return jwt().jwt(j -> j.subject("kc-" + role).claim("tenant_id", tenantId.toString())
                .claim("email", role + "@romashka.ru").claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor owner() {
        return as(tenant, "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String id(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(mvc.perform(request.with(owner())).andExpect(status().is2xxSuccessful()).andReturn()
                .getResponse().getContentAsString(), "$.id");
    }

    private void company(String name) throws Exception {
        mvc.perform(json(put("/api/v1/company"), "{\"name\":\"%s\",\"currency\":\"RUB\"}".formatted(name))
                .with(owner())).andExpect(status().isOk());
    }

    private Set<String> tablesWithTenantData() {
        Set<String> tables = new TreeSet<>(admin.sql("""
                select table_name from information_schema.columns
                where table_schema = 'public' and column_name = 'tenant_id'
                """).query(String.class).list());
        tables.add("tenant");
        return tables;
    }

    @Test
    void everyTableWithTenantDataHasAHolder() {
        Set<String> held = holders.stream().flatMap(h -> h.tables().stream()).collect(Collectors.toCollection(TreeSet::new));
        held.addAll(KEPT);
        assertThat(held).isEqualTo(tablesWithTenantData());
    }

    @Test
    void theRequestNeedsTheCompanyNameAndCanBeCancelledDuringTheGracePeriod() throws Exception {
        mvc.perform(json(post("/api/v1/company/erasure"), "{\"confirmName\":\"x\"}").with(owner()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("company_not_found"));
        company("ООО «Ромашка»");
        mvc.perform(json(post("/api/v1/company/erasure"), "{\"confirmName\":\"Ромашка\"}").with(owner()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("confirm_name_mismatch"));
        mvc.perform(json(post("/api/v1/company/erasure"), "{\"confirmName\":\"ООО «Ромашка»\"}")
                        .with(as(tenant, "it_admin")))
                .andExpect(status().isForbidden());

        String scheduled = mvc.perform(json(post("/api/v1/company/erasure"), "{\"confirmName\":\"ООО «Ромашка»\"}")
                        .with(owner()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("scheduled"))
                .andReturn().getResponse().getContentAsString();
        Instant requested = Instant.parse(JsonPath.read(scheduled, "$.requestedAt"));
        Instant due = Instant.parse(JsonPath.read(scheduled, "$.scheduledFor"));
        assertThat(ChronoUnit.DAYS.between(requested, due)).isEqualTo(30);

        mvc.perform(delete("/api/v1/company/erasure").with(owner())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/company/erasure").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(delete("/api/v1/company/erasure").with(owner())).andExpect(status().isNotFound());
        assertThat(service.eraseDueCompanies()).isZero();
        mvc.perform(get("/api/v1/company").with(owner())).andExpect(status().isOk());
    }

    @Test
    void erasingTheCompanyLeavesNoRowOfItAnywhere() throws Exception {
        UUID other = UUID.randomUUID();
        mvc.perform(json(post("/api/v1/budgets"), "{\"name\":\"B\",\"level\":\"company\",\"limit\":1,\"currency\":\"RUB\"}")
                .with(as(other, "owner"))).andExpect(status().isCreated());
        ACCOUNTS.put("kc-" + tenant, new Account("kc-" + tenant, "a@romashka.ru", tenant));
        ACCOUNTS.put("kc-" + other, new Account("kc-" + other, "a@other.ru", other));

        // A company that used most of the product.
        company("ООО «Ромашка»");
        String department = id(json(post("/api/v1/departments"), "{\"name\":\"ИТ\"}"));
        String employee = id(json(post("/api/v1/employees"), """
                {"name":"Иванов","email":"ivanov@romashka.ru","departmentId":"%s","position":"П"}""".formatted(department)));
        id(json(post("/api/v1/users"), "{\"name\":\"И\",\"email\":\"ivanov@romashka.ru\",\"role\":\"finance\"}"));
        String budget = id(json(post("/api/v1/budgets"),
                "{\"name\":\"B\",\"level\":\"company\",\"limit\":1000,\"currency\":\"RUB\"}"));
        String mandate = id(json(post("/api/v1/mandates"), """
                {"name":"M","models":["gpt-5"],"vendors":["OpenAI"],"maxPerOperation":500,"approvalThreshold":300,
                 "hours":"09:00–21:00"}"""));
        mvc.perform(put("/api/v1/mandates/{id}/publication", mandate).with(owner())).andExpect(status().isOk());
        String agent = id(json(post("/api/v1/agents"), """
                {"name":"Bot","description":"d","framework":"LangChain","departmentId":"%s","ownerId":"%s",
                 "mandateId":"%s"}""".formatted(department, employee, mandate)));
        mvc.perform(post("/api/v1/agents/{id}/keys", agent).with(owner())).andExpect(status().isCreated());
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-1","model":"ThinkPad","type":"LAPTOP","cost":80000}"""));
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(employee))
                .with(owner())).andExpect(status().isOk());
        String connection = id(json(post("/api/v1/connections"),
                "{\"type\":\"okta\",\"name\":\"Okta\",\"settings\":{\"url\":\"https://r.okta.example\"}}"));
        runs.recorded(new ConnectorRuns.Run(UUID.randomUUID(), tenant, UUID.fromString(connection), "okta",
                Instant.now().minusSeconds(60), Instant.now(), true, 3, null));
        mvc.perform(json(post("/api/v1/policies/rules"),
                "{\"name\":\"R\",\"expression\":\"cost > 100.0\",\"effect\":\"DENY\",\"enabled\":true}").with(owner()))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/publication").with(owner())).andExpect(status().is2xxSuccessful());
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("file", "invoice.csv", "text/csv",
                "Поставщик;Сумма\nЯндекс;1500\n".getBytes())).with(owner())).andExpect(status().is2xxSuccessful());
        mvc.perform(put("/api/v1/employees/{id}/dismissal", employee).with(owner())).andExpect(status().isOk());
        receipts.issue(tenant, UUID.randomUUID(), UUID.fromString(agent), new BigDecimal("12.50"), "RUB",
                Instant.now());
        Path files = Path.of(System.getProperty("java.io.tmpdir"), "costguard-imports", tenant.toString());
        assertThat(files).isDirectory();
        assertThat(rowsOf(tenant)).containsKeys("employee", "agent", "asset_act", "receipt", "connection_run",
                "import_job", "activity_entry", "notification", "tenant");

        mvc.perform(json(post("/api/v1/company/erasure"), "{\"confirmName\":\"ООО «Ромашка»\"}").with(owner()))
                .andExpect(status().isAccepted());
        assertThat(service.eraseDueCompanies()).as("the grace period has not ended").isZero();
        admin.sql("update company_erasure set scheduled_for = now() - interval '1 minute' where tenant_id = ?")
                .param(tenant).update();
        CLICKHOUSE.clear();

        assertThat(service.eraseDueCompanies()).isEqualTo(1);

        assertThat(rowsOf(tenant)).as("rows of the erased company").isEmpty();
        assertThat(rowsOf(other)).containsKey("budget");
        assertThat(files).doesNotExist();
        assertThat(ACCOUNTS).doesNotContainKey("kc-" + tenant).containsKey("kc-" + other);
        assertThat(CLICKHOUSE).containsExactly(
                "ALTER TABLE cost_records DELETE WHERE tenant_id = toUUID('" + tenant + "')",
                "ALTER TABLE agent_operations DELETE WHERE tenant_id = toUUID('" + tenant + "')");
        CompactedRecordsErased sent = tombstones.seen.stream().filter(t -> t.tenantId().equals(tenant)).findFirst()
                .orElseThrow();
        assertThat(sent.keys()).contains(new CompactedRecordsErased.Key("connectors.config.v1", tenant + ":okta"),
                new CompactedRecordsErased.Key("agents.state.v1", agent),
                new CompactedRecordsErased.Key("budgets.limits.v1", budget),
                new CompactedRecordsErased.Key("policy.snapshots.v1", tenant.toString()));

        String record = admin.sql("select report::text from company_erasure where tenant_id = ? and completed_at"
                + " is not null").param(tenant).query(String.class).single();
        assertThat(JsonPath.<List<String>>read(record, "$[?(@.action == 'deleted')].store"))
                .contains("org.employee", "journal.receipt", "assets.asset_act", "imports.files", "keycloak.user",
                        "clickhouse.cost_records", "activity.activity_entry", "org.tenant");
        assertThat(record).doesNotContain("ivanov", "Иванов");
        assertThat(service.eraseDueCompanies()).as("done once").isZero();
    }

    /** Rows per table for the tenant, read past RLS; tables without rows are left out. */
    private Map<String, Long> rowsOf(UUID tenantId) {
        Map<String, Long> rows = new java.util.TreeMap<>();
        for (String table : tablesWithTenantData()) {
            if (KEPT.contains(table)) {
                continue;
            }
            String column = table.equals("tenant") ? "id" : "tenant_id";
            long n = admin.sql("select count(*) from " + table + " where " + column + " = ?").param(tenantId)
                    .query(Long.class).single();
            if (n > 0) {
                rows.put(table, n);
            }
        }
        return rows;
    }
}
