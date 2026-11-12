package ai.costguard.core.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * payroll_unmatched (V48, finding R1 of 2026-11-12) holds payments paid to nobody: the address and the name a source
 * reported, and the amount. Checked on real rows: the forced RLS for the application role, and the erasures of a
 * person and of a company, which must reach these rows and only these. All data is fictitious.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayrollUnmatchedProtectionIntegrationTest extends PrivacyTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ai.costguard.core.shared.security.RealmRoles realmRoles;
    @Autowired PrivacyService service;

    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());

    private UUID row(UUID tenantId, String email, String name, String reason) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        su.sql("""
                insert into payroll_unmatched (id, tenant_id, fingerprint, source, reported_email, reported_name, kind,
                                               period, pay_date, amount, currency, reason, candidates, first_seen,
                                               last_seen)
                values (?, ?, ?, 'excel:vedomost.csv', ?, ?, 'salary', date '2026-10-01', date '2026-10-10', 12345.67,
                        'RUB', ?, 0, ?, ?)""")
                .params(id, tenantId, UUID.randomUUID().toString(), email, name, reason, now, now).update();
        return id;
    }

    private List<UUID> idsOf(UUID tenantId) {
        return su.sql("select id from payroll_unmatched where tenant_id = ? order by id").param(tenantId)
                .query(UUID.class).list();
    }

    // RLS on real rows, as the application role

    @Test
    void theApplicationRoleSeesTheRowsOfTheBoundCompanyOnly() {
        UUID ofA = row(tenant, "a-" + run + "@example.com", null, "employee not found");
        UUID ofB = row(other, "b-" + run + "@example.com", null, "employee not found");
        var connection = new SingleConnectionDataSource(jdbcUrl(), "costguard", "", true);
        try {
            JdbcClient app = JdbcClient.create(connection);
            assertThat(app.sql("select rolbypassrls or rolsuper from pg_roles where rolname = current_user")
                    .query(Boolean.class).single()).as("the application role is held by RLS").isFalse();
            app.sql("select set_config('app.tenant_id', '', false)").query(String.class).single();
            assertThat(app.sql("select count(*) from payroll_unmatched").query(Long.class).single())
                    .as("no company bound: no row at all").isZero();

            app.sql("select set_config('app.tenant_id', ?, false)").param(tenant.toString()).query(String.class).single();
            assertThat(app.sql("select id from payroll_unmatched").query(UUID.class).list()).containsExactly(ofA);
            assertThat(app.sql("select count(*) from payroll_unmatched where id = ?").param(ofB).query(Long.class)
                    .single()).as("company A reads B's row by its id").isZero();
            assertThat(app.sql("update payroll_unmatched set reason = 'bad email' where id = ?").param(ofB).update())
                    .as("company A changes B's row").isZero();
            assertThatThrownBy(() -> app.sql("""
                    insert into payroll_unmatched (id, tenant_id, fingerprint, source, kind, period, pay_date, amount,
                                                   currency, reason, first_seen, last_seen)
                    values (?, ?, 'x', 's', 'salary', date '2026-10-01', date '2026-10-10', 1, 'RUB',
                            'employee not found', now(), now())""").params(UUID.randomUUID(), other).update())
                    .as("company A writes a row of B").satisfies(e -> assertThat(((java.sql.SQLException)
                            org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e)).getSQLState())
                            .as("insufficient privilege: the row-level security policy").isEqualTo("42501"));

            app.sql("select set_config('app.tenant_id', ?, false)").param(other.toString()).query(String.class).single();
            assertThat(app.sql("select id from payroll_unmatched").query(UUID.class).list()).containsExactly(ofB);
        } finally {
            connection.destroy();
        }
        assertThat(su.sql("select reason from payroll_unmatched where id = ?").param(ofB).query(String.class).single())
                .isEqualTo("employee not found");
    }

    @Test
    void theTableForcesRlsAndCarriesOnlyItsTwoPolicies() {
        Map<String, Object> rls = su.sql("""
                select relrowsecurity, relforcerowsecurity from pg_class
                where relname = 'payroll_unmatched' and relnamespace = 'public'::regnamespace""").query().singleRow();
        assertThat(rls).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
        assertThat(su.sql("""
                select policyname || ':' || cmd || ':' || roles::text from pg_policies
                where tablename = 'payroll_unmatched' order by policyname""").query(String.class).list())
                .containsExactly("backup_read:SELECT:{public}", "tenant_isolation:ALL:{public}");
    }

    // the erasure of a person

    private RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-boss-" + run).claim("tenant_id", tenant.toString())
                .claim("email", "boss-" + run + "@romashka.example")
                .claim("realm_access", Map.of("roles", List.of("owner")))).authorities(realmRoles);
    }

    private String created(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(owner()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void erasingAPersonTakesTheirRowsByAddressAndByNameAndNoOneElses() throws Exception {
        String email = "petrova-" + run + "@romashka.example";
        String department = created(post("/api/v1/departments").content("{\"name\":\"Отдел %s\"}".formatted(run)));
        String employee = created(post("/api/v1/employees").content("""
                {"name":"Петрова Алёна Сергеевна","email":"%s","departmentId":"%s","position":"Инженер"}
                """.formatted(email, department)));
        UUID byAddress = row(tenant, email.toUpperCase(), null, "ambiguous employee");
        UUID byAddressAndName = row(tenant, email, "Петрова Алёна Сергеевна", "employee not found");
        // the same person by name only, written as a sheet may write it
        UUID byName = row(tenant, null, "ПЕТРОВА  Алена Сергеевна", "ambiguous employee");
        UUID someoneElse = row(tenant, null, "Иванов Иван Иванович", "employee not found");
        UUID otherAddress = row(tenant, "ivanov-" + run + "@romashka.example", "Петрова Алёна Сергеевна",
                "employee not found");
        UUID otherCompany = row(other, email, "Петрова Алёна Сергеевна", "employee not found");

        mvc.perform(put("/api/v1/employees/{id}/dismissal", employee).with(owner()))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/v1/employees/{id}/erasure", employee).with(owner()))
                .andExpect(status().is2xxSuccessful());

        assertThat(idsOf(tenant)).as("rows left in the company").containsExactlyInAnyOrder(someoneElse, otherAddress)
                .doesNotContain(byAddress, byAddressAndName, byName);
        assertThat(idsOf(other)).as("the other company's row").containsExactly(otherCompany);
    }

    // the erasure of a company

    @Test
    void erasingACompanyTakesAllItsRowsAndKeepsTheOtherCompanys() {
        UUID ofOther = row(other, "keep-" + run + "@example.com", "Сохранённый Сотрудник", "employee not found");
        row(tenant, "gone-1-" + run + "@example.com", null, "employee not found");
        row(tenant, null, "Сотрудник Без Адреса", "ambiguous employee");
        row(tenant, "bad@", "Сотрудник С Плохим Адресом", "bad email");
        su.sql("insert into tenant (id, name) values (?, 'ООО Стираемая')").param(tenant).update();
        su.sql("""
                insert into company_erasure (tenant_id, requested_at, scheduled_for)
                values (?, now() - interval '31 days', now() - interval '1 minute')""").param(tenant).update();

        assertThat(service.eraseDueCompanies()).isGreaterThanOrEqualTo(1);

        assertThat(idsOf(tenant)).as("rows of the erased company").isEmpty();
        assertThat(idsOf(other)).containsExactly(ofOther);
    }
}
