package ai.costguard.core.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OrgApiIntegrationTest.Dismissals.class)
class OrgApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Dismissals {
        final List<EmployeeDismissed> seen = new ArrayList<>();

        @EventListener
        void on(EmployeeDismissed event) {
            seen.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Dismissals dismissals;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as(tenant, "it_admin");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String department() throws Exception {
        String body = mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"%s\"}".formatted(UUID.randomUUID()))
                        .with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String person(String departmentId, String email) {
        return """
                {"name":"Anna","email":"%s","departmentId":"%s","position":"Engineer"}
                """.formatted(email, departmentId);
    }

    private String employee() throws Exception {
        String body = mvc.perform(json(post("/api/v1/employees"), person(department(), UUID.randomUUID() + "@x.ru"))
                        .with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String user(String role) throws Exception {
        String body = mvc.perform(json(post("/api/v1/users"), """
                        {"name":"U","email":"%s@x.ru","role":"%s"}
                        """.formatted(UUID.randomUUID(), role)).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void companyIsCreatedThenUpdated() throws Exception {
        mvc.perform(get("/api/v1/company").with(admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("company_not_found"));
        mvc.perform(json(put("/api/v1/company"), "{\"name\":\"Acme\",\"currency\":\"RUB\"}").with(admin()))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/company"), "{\"name\":\"Acme 2\",\"currency\":\"USD\"}").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme 2"));
        mvc.perform(get("/api/v1/company").with(admin())).andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        String id = employee();
        UUID other = UUID.randomUUID();

        // a role that may read (since ADR 0051 a token without a staff role reads nothing)
        mvc.perform(get("/api/v1/employees/" + id).with(as(other, "auditor"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/employees").with(as(other, "auditor"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/departments").with(as(other, "auditor"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(put("/api/v1/employees/" + id + "/dismissal").with(as(other, "owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anEmployeeCannotJoinADepartmentOfAnotherTenant() throws Exception {
        String foreign = department();
        UUID other = UUID.randomUUID();

        mvc.perform(json(post("/api/v1/employees"), person(foreign, "a@x.ru")).with(as(other, "owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("department_not_found"));
    }

    @Test
    void emailIsUniqueInsideATenantOnly() throws Exception {
        String department = department();
        mvc.perform(json(post("/api/v1/employees"), person(department, "same@x.ru")).with(admin()))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/employees"), person(department, "same@x.ru")).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    void financeCanReadButNotChange() throws Exception {
        mvc.perform(get("/api/v1/employees").with(as(tenant, "finance"))).andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"X\"}").with(as(tenant, "finance")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
    }

    @Test
    void dismissalIsIdempotentAndPublishedOnce() throws Exception {
        String id = employee();
        UUID employeeId = UUID.fromString(id);

        mvc.perform(put("/api/v1/employees/" + id + "/dismissal").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/api/v1/employees/" + id + "/dismissal").with(admin())).andExpect(status().isOk());

        assertThat(dismissals.seen.stream().filter(e -> e.employeeId().equals(employeeId))).hasSize(1);
    }

    @Test
    void checklistShowsHeldEquipment() throws Exception {
        String id = employee();
        String asset = JsonPath.read(mvc.perform(json(post("/api/v1/assets"), """
                        {"inventoryNumber":"%s","model":"ThinkPad","type":"LAPTOP","cost":80000.00}
                        """.formatted(UUID.randomUUID())).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/assets/" + asset + "/assignment"), "{\"employeeId\":\"%s\"}".formatted(id))
                .with(admin())).andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees/" + id + "/dismissal-checklist").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(false))
                .andExpect(jsonPath("$.sections[?(@.name == 'equipment')].items[0].cost").value(80000.00))
                .andExpect(jsonPath("$.sections[?(@.name == 'equipment')].totalCost").value(80000.00));
    }

    @Test
    void theLastOwnerCannotBeDemoted() throws Exception {
        String owner = user("owner");
        mvc.perform(json(put("/api/v1/users/" + owner + "/role"), "{\"role\":\"auditor\"}").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_owner"));

        user("owner");
        mvc.perform(json(put("/api/v1/users/" + owner + "/role"), "{\"role\":\"auditor\"}").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("auditor"));
    }

    @Test
    void aUserIsRemovedButNotTheLastOwner() throws Exception {
        String owner = user("owner");
        mvc.perform(delete("/api/v1/users/" + owner).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_owner"));

        String auditor = user("auditor");
        mvc.perform(delete("/api/v1/users/" + auditor).with(as(tenant, "finance")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/" + auditor).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users").with(admin()))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(auditor)).isEmpty());
        mvc.perform(delete("/api/v1/users/" + auditor).with(admin())).andExpect(status().isNotFound());
    }
}
