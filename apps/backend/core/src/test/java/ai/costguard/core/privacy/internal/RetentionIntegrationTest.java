package ai.costguard.core.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class RetentionIntegrationTest extends PrivacyTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired PrivacyService service;

    private final UUID tenant = UUID.randomUUID();
    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-owner").claim("tenant_id", tenant.toString())
                .claim("email", "owner@romashka.ru").claim("realm_access", Map.of("roles", List.of("owner"))))
                .authorities(realmRoles);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String id(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(mvc.perform(request.with(owner())).andExpect(status().is2xxSuccessful()).andReturn()
                .getResponse().getContentAsString(), "$.id");
    }

    private String employee(String email) throws Exception {
        String department = id(json(post("/api/v1/departments"), "{\"name\":\"%s\"}".formatted(UUID.randomUUID())));
        return id(json(post("/api/v1/employees"), """
                {"name":"Сидоров","email":"%s","departmentId":"%s","position":"П"}""".formatted(email, department)));
    }

    private long count(String sql) {
        return admin.sql(sql).param(tenant).query(Long.class).single();
    }

    @Test
    void whatIsPastItsTermGoesAndTheRestStays() throws Exception {
        String longGone = employee("gone@romashka.ru");
        String recent = employee("recent@romashka.ru");
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-%s","model":"X","type":"LAPTOP","cost":1}""".formatted(UUID.randomUUID())));
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(recent))
                .with(owner())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/assets/{id}/assignment", asset).with(owner())).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/employees/{id}/dismissal", longGone).with(owner())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/employees/{id}/dismissal", recent).with(owner())).andExpect(status().isOk());

        // Age the data: what the clock would have done.
        admin.sql("update employee set dismissed_at = current_date - 400 where id = ?").param(UUID.fromString(longGone))
                .update();
        admin.sql("update asset_assignment set issued_at = current_date - 2300, returned_at = current_date - 2200"
                + " where tenant_id = ?").param(tenant).update();
        admin.sql("""
                insert into notification (id, tenant_id, at, text, level) values
                    (gen_random_uuid(), ?, now() - interval '400 days', 'старое', 'info'),
                    (gen_random_uuid(), ?, now() - interval '10 days', 'новое', 'info')
                """).params(tenant, tenant).update();
        admin.sql("""
                insert into activity_entry (id, tenant_id, at, system, action, target_type)
                values (gen_random_uuid(), ?, now() - interval '4 years', 'core', 'budget.created', 'budget')
                """).param(tenant).update();

        assertThat(service.applyRetention()).isPositive();

        assertThat(count("select count(*) from notification where tenant_id = ? and text = 'старое'")).isZero();
        assertThat(count("select count(*) from notification where tenant_id = ? and text = 'новое'")).isOne();
        assertThat(count("select count(*) from asset_act where tenant_id = ?")).isZero();
        assertThat(count("select count(*) from activity_entry where tenant_id = ? and at < now() - interval '3 years'"))
                .isZero();
        // The employee dismissed over a year ago was erased by the job; the other one stays as they are.
        mvc.perform(get("/api/v1/employees/{id}", longGone).with(owner())).andExpect(jsonPath("$.erased").value(true));
        mvc.perform(get("/api/v1/employees/{id}", recent).with(owner()))
                .andExpect(jsonPath("$.email").value("recent@romashka.ru"));
        mvc.perform(get("/api/v1/erasures").with(owner()))
                .andExpect(jsonPath("$[0].employeeId").value(longGone))
                .andExpect(jsonPath("$[0].reason").value("retention"));
        String log = mvc.perform(get("/api/v1/activity").param("limit", "200").with(owner()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(log, "$[?(@.system == 'retention')].action"))
                .contains("privacy.retention_applied", "privacy.employee_erased");
    }

    @Test
    void outsideTheJobEvidenceCannotBeDeleted() throws Exception {
        String employee = employee("e@romashka.ru");
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-%s","model":"X","type":"LAPTOP","cost":1}""".formatted(UUID.randomUUID())));
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(employee))
                .with(owner())).andExpect(status().isOk());
        assertThatThrownBy(() -> admin.sql("delete from asset_act where tenant_id = ?").param(tenant).update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> admin.sql("delete from activity_entry where tenant_id = ?").param(tenant).update())
                .hasMessageContaining("append-only");
    }
}
