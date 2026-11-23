package ai.costguard.core.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeErasureIntegrationTest extends PrivacyTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());

    @AfterEach
    void keycloakUp() {
        keycloakDown = false;
    }

    private RequestPostProcessor as(String subject, String email, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", tenant.toString()).claim("email", email)
                .claim("name", "N").claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private RequestPostProcessor owner() {
        return as("kc-boss", "boss@romashka.ru", "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who) throws Exception {
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString();
    }

    private String id(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(body(request, owner()), "$.id");
    }

    private String employee(String email, String role) throws Exception {
        String department = id(json(post("/api/v1/departments"), "{\"name\":\"%s\"}".formatted(UUID.randomUUID())));
        String employee = id(json(post("/api/v1/employees"), """
                {"name":"Петров Иван Сергеевич","email":"%s","departmentId":"%s","position":"Инженер"}
                """.formatted(email, department)));
        id(json(post("/api/v1/users"), """
                {"name":"Петров И. С.","email":"%s","role":"%s","employeeId":"%s"}
                """.formatted(email.toUpperCase(), role, employee)));
        return employee;
    }

    private void dismiss(String employee) throws Exception {
        body(put("/api/v1/employees/{id}/dismissal", employee), owner());
    }

    /** ADR 0079: the person's data from every module, machine-readable, owner only; secrets stay out. */
    @Test
    void theOwnerExportsWhatEveryModuleHoldsAboutAPerson() throws Exception {
        String email = "export@romashka.ru";
        String employee = employee(email, "finance");
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-%s","model":"ThinkPad","type":"LAPTOP","cost":80000}
                """.formatted(UUID.randomUUID())));
        body(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(employee)),
                owner());
        body(json(post("/api/v1/connections"), """
                {"type":"google_workspace","name":"GW","settings":{"admin":"%s"}}""".formatted(email)), owner());
        admin.sql("""
                insert into approval_request (id, tenant_id, operation_id, agent_id, description, amount, reason,
                                              status, created_at, expires_at, decided_by)
                values (?, ?, ?, ?, 'd', 10, 'r', 'approved', now(), now(), ?)
                """).params(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), email).update();
        admin.sql("""
                insert into signing_key (tenant_id, email, method, pin_hash, updated_at)
                values (?, ?, 'pin', 'secret-pin-hash', now())""").params(tenant, email).update();

        mvc.perform(get("/api/v1/employees/{id}/personal-data", employee).with(as("kc-f", "f@romashka.ru",
                "finance"))).andExpect(status().isForbidden());
        String export = body(get("/api/v1/employees/{id}/personal-data", employee), owner());

        org.assertj.core.api.Assertions.assertThat(export).doesNotContain("secret-pin-hash");
        mvc.perform(get("/api/v1/employees/{id}/personal-data", employee).with(owner()))
                .andExpect(jsonPath("$.erased").value(false))
                .andExpect(jsonPath("$.tables['org.employee'][0].email").value(email))
                .andExpect(jsonPath("$.tables['org.user_account'][0].role").value("finance"))
                .andExpect(jsonPath("$.tables['assets.asset_assignment'].length()").value(1))
                .andExpect(jsonPath("$.tables['approvals.approval_request'][0].decided_by").value(email))
                .andExpect(jsonPath("$.tables['connections.connection.settings'][0].setting").value("admin"))
                .andExpect(jsonPath("$.tables['cabinet.signing_key'][0].method").value("pin"))
                .andExpect(jsonPath("$.tables['cabinet.vacation_request'].length()").value(0));
        mvc.perform(get("/api/v1/employees/{id}/personal-data", UUID.randomUUID()).with(owner()))
                .andExpect(status().isNotFound());

        // after erasure the export shows what is left: the anonymized record, nothing that names the person
        dismiss(employee);
        body(post("/api/v1/employees/{id}/erasure", employee), owner());
        String after = body(get("/api/v1/employees/{id}/personal-data", employee), owner());
        org.assertj.core.api.Assertions.assertThat(after).doesNotContain(email).contains("\"erased\":true");
    }

    @org.springframework.beans.factory.annotation.Autowired
    java.util.List<ai.costguard.core.privacy.DataHolder> holders;

    /** A module that erases something about a person must also export it (ADR 0079). */
    @Test
    void everyModuleThatErasesAPersonAlsoExportsThem() throws Exception {
        for (ai.costguard.core.privacy.DataHolder holder : holders) {
            Class<?> type = org.springframework.aop.support.AopUtils.getTargetClass(holder);
            boolean erases = declares(type, "eraseEmployee");
            org.assertj.core.api.Assertions.assertThat(!erases || declares(type, "exportEmployee"))
                    .as(type.getName() + " erases a person but does not export them").isTrue();
        }
    }

    private static boolean declares(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name));
    }

    @Test
    void anActiveEmployeeIsNotErased() throws Exception {
        String employee = employee("active@romashka.ru", "finance");
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("employee_active"));
        mvc.perform(post("/api/v1/employees/{id}/erasure", UUID.randomUUID()).with(owner()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("employee_not_found"));
    }

    @Test
    void onlyTheOwnerErases() throws Exception {
        String employee = employee("x@romashka.ru", "finance");
        dismiss(employee);
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(as("kc-it", "it@romashka.ru", "it_admin")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
        mvc.perform(get("/api/v1/erasures").with(as("kc-f", "f@romashka.ru", "finance")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOnlyOwnerOfTheCompanyIsNotErased() throws Exception {
        String employee = employee("sole@romashka.ru", "owner");
        dismiss(employee);
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_owner"));
    }

    @Test
    void whenKeycloakIsDownNothingIsErased() throws Exception {
        String employee = employee("down@romashka.ru", "finance");
        dismiss(employee);
        keycloakDown = true;
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("identity_provider_unavailable"));
        mvc.perform(get("/api/v1/employees/{id}", employee).with(owner()))
                .andExpect(jsonPath("$.email").value("down@romashka.ru")).andExpect(jsonPath("$.erased").value(false));
    }

    @Test
    void erasingAnEmployeeReachesEveryStoreAndKeepsWhatTheLawKeeps() throws Exception {
        String email = "petrov@romashka.ru";
        String employee = employee(email, "finance");
        UUID employeeId = UUID.fromString(employee);
        ACCOUNTS.put("kc-petrov", new Account("kc-petrov", email, tenant));
        UUID elsewhere = UUID.randomUUID();
        ACCOUNTS.put("kc-namesake", new Account("kc-namesake", email, elsewhere));

        // The person did things as a user: a budget, which the activity log records under their name.
        body(json(post("/api/v1/budgets"), "{\"name\":\"B\",\"level\":\"company\",\"limit\":1,\"currency\":\"RUB\"}"),
                as("kc-petrov", email, "finance"));
        // A device issued and returned, with its act.
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-%s","model":"ThinkPad","type":"LAPTOP","cost":80000}
                """.formatted(UUID.randomUUID())));
        body(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(employee)),
                owner());
        body(delete("/api/v1/assets/{id}/assignment", asset), owner());
        // A connection whose settings name them.
        body(json(post("/api/v1/connections"), """
                {"type":"google_workspace","name":"GW","settings":{"admin":"%s"}}""".formatted(email)), owner());
        // What other modules keep about them, written as those modules write it.
        UUID vendor = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        admin.sql("insert into vendor (id, tenant_id, name) values (?, ?, ?)").params(vendor, tenant, "V").update();
        admin.sql("""
                insert into subscription (id, tenant_id, vendor_id, product, department_id, seats, price_per_seat,
                                          billing_period, renewal_date)
                values (?, ?, ?, 'P', ?, 1, 1, 'month', current_date)
                """).params(subscription, tenant, vendor, UUID.randomUUID()).update();
        admin.sql("""
                insert into license_assignment (id, tenant_id, subscription_id, employee_id, last_active)
                values (?, ?, ?, ?, current_date)""").params(UUID.randomUUID(), tenant, subscription, employeeId)
                .update();
        UUID approval = UUID.randomUUID();
        admin.sql("""
                insert into approval_request (id, tenant_id, operation_id, agent_id, description, amount, reason,
                                              status, created_at, expires_at, decided_by)
                values (?, ?, ?, ?, 'd', 10, 'r', 'approved', now(), now(), ?)
                """).params(approval, tenant, UUID.randomUUID(), UUID.randomUUID(), "Petrov@Romashka.ru").update();
        admin.sql("""
                insert into ingest_entity (tenant_id, entity_key, entity_type, source_id, payload, confidence,
                                           first_seen, last_seen)
                values (?, ?, 'EMPLOYEE', ?, cast(? as jsonb), 1, now(), now()),
                       (?, ?, 'PAYMENT', ?, cast(? as jsonb), 1, now(), now())
                """).params(tenant, tenant + ":employee:email:" + email, UUID.randomUUID(),
                "{\"email\":\"" + email + "\"}", tenant, tenant + ":payment:tbank:1", UUID.randomUUID(),
                "{\"payer\":\"PETROV@romashka.ru\",\"amount\":\"990\"}").update();
        dismiss(employee);

        String record = mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String erasure = JsonPath.read(record, "$.id");

        // The employee row stays, naming nobody; the users and the Keycloak account of this company are gone.
        mvc.perform(get("/api/v1/employees/{id}", employee).with(owner()))
                .andExpect(jsonPath("$.name").value("Удалённый сотрудник"))
                .andExpect(jsonPath("$.erased").value(true))
                .andExpect(jsonPath("$.email").value("erased-" + employee + "@erased.invalid"));
        assertThat(JsonPath.<List<String>>read(body(get("/api/v1/users"), owner()), "$[*].email"))
                .noneMatch(e -> e.equalsIgnoreCase(email));
        assertThat(ACCOUNTS).doesNotContainKey("kc-petrov").containsKey("kc-namesake");
        assertThat(count("select count(*) from license_assignment where employee_id = ?", employeeId)).isZero();
        assertThat(admin.sql("select decided_by from approval_request where id = ?").param(approval)
                .query(String.class).single()).isEqualTo("erased:" + erasure);
        assertThat(count("select count(*) from ingest_entity where tenant_id = ? and entity_type = 'EMPLOYEE'",
                tenant)).isZero();
        assertThat(admin.sql("select payload::text from ingest_entity where tenant_id = ?").param(tenant)
                .query(String.class).single()).doesNotContainIgnoringCase(email).contains("erased:" + erasure);

        // The activity log keeps the entries and forgets the name.
        String log = body(get("/api/v1/activity").param("action", "budget.created"), owner());
        assertThat(JsonPath.<List<Boolean>>read(log, "$[*].actor.erased")).containsExactly(true);
        assertThat(log).doesNotContainIgnoringCase(email);
        mvc.perform(get("/api/v1/activity").param("action", "privacy.employee_erased").with(owner()))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].actor.email").value("boss@romashka.ru"))
                .andExpect(jsonPath("$[0].details.erasureId").value(erasure));

        // The record says what each store did, and what stays and why.
        assertThat(step(record, "keycloak.user", "deleted")).containsEntry("rows", 1);
        assertThat(step(record, "org.user_account", "deleted")).containsEntry("rows", 1);
        assertThat(step(record, "org.employee", "anonymized")).containsEntry("rows", 1);
        assertThat(step(record, "software.license_assignment", "deleted")).containsEntry("rows", 1);
        assertThat(step(record, "activity.activity_actor", "anonymized")).containsEntry("rows", 1);
        assertThat(step(record, "approvals.approval_request.decided_by", "anonymized")).containsEntry("rows", 1);
        assertThat(step(record, "assets.asset_act", "retained"))
                .containsEntry("until", LocalDate.now(java.time.ZoneOffset.UTC).plusYears(5).toString());
        assertThat(step(record, "journal.receipt", "retained")).containsKey("note");
        assertThat((String) step(record, "connections.connection.settings", "manual").get("note"))
                .contains("admin", "google_workspace");
        assertThat(record).doesNotContainIgnoringCase(email).doesNotContain("Петров");

        // Asking again gives the same record; the auditor can read it; the erased row cannot be edited back.
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(erasure));
        mvc.perform(get("/api/v1/erasures/{id}", erasure).with(as("kc-a", "a@romashka.ru", "auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value(employee));
        mvc.perform(json(put("/api/v1/employees/{id}", employee), """
                {"name":"Петров","email":"%s","departmentId":"%s","position":"P"}""".formatted(email,
                UUID.randomUUID())).with(owner()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("employee_erased"));
    }

    private long count(String sql, Object param) {
        return admin.sql(sql).param(param).query(Long.class).single();
    }

    private static Map<String, Object> step(String record, String store, String action) {
        List<Map<String, Object>> found = JsonPath.read(record,
                "$.steps[?(@.store == '" + store + "' && @.action == '" + action + "')]");
        assertThat(found).as("%s %s in %s", action, store, record).hasSize(1);
        return found.get(0);
    }
}
