package ai.costguard.core.cabinet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** «Мой кабинет» (ADR 0051): the role employee sees itself and nothing of the company. */
@SpringBootTest
@AutoConfigureMockMvc
class CabinetApiIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as("admin@corp.ru", "it_admin");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String employee(String email) throws Exception {
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                        "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String body = mvc.perform(json(post("/api/v1/employees"), """
                        {"name":"Иванов Иван","email":"%s","departmentId":"%s","position":"Разработчик"}
                        """.formatted(email, department)).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void anEmployeeSeesThemselves() throws Exception {
        String email = "ivanov-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        mvc.perform(get("/api/v1/me").with(as(email, "employee", "offline_access")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.id").value(id))
                .andExpect(jsonPath("$.employee.position").value("Разработчик"))
                .andExpect(jsonPath("$.name").value("Иванов Иван"))
                .andExpect(jsonPath("$.roles[0]").value("employee"))
                .andExpect(jsonPath("$.roles.length()").value(1)); // offline_access is Keycloak's, not ours
    }

    @Test
    void anEmployeeSeesNothingOfTheCompany() throws Exception {
        String email = "petrova-%s@corp.ru".formatted(UUID.randomUUID());
        employee(email);
        for (String path : List.of("/api/v1/employees", "/api/v1/users", "/api/v1/departments", "/api/v1/findings",
                "/api/v1/subscriptions", "/api/v1/assets", "/api/v1/budgets", "/api/v1/agents",
                "/api/v1/notifications", "/api/v1/imports", "/api/v1/company")) {
            mvc.perform(get(path).with(as(email, "employee"))).andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"x\"}").with(as(email, "employee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeKeepsTheirOwnDeliverySettings() throws Exception {
        String email = "smirnov-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        // invited as an employee (V34 lets user records carry the role)
        mvc.perform(json(post("/api/v1/users"), """
                        {"name":"Смирнов Олег","email":"%s","role":"employee","employeeId":"%s"}
                        """.formatted(email, id)).with(as("owner@corp.ru", "owner")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/notifications/settings").with(as(email, "employee"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(as(email, "employee")))
                .andExpect(jsonPath("$.employee.id").value(id))
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void staffKeepTheirAccessAndMayHaveACabinetToo() throws Exception {
        mvc.perform(get("/api/v1/employees").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee").doesNotExist())
                .andExpect(jsonPath("$.roles[0]").value("it_admin"));
    }

    @Test
    void aDismissedEmployeeHasNoCabinet() throws Exception {
        String email = "sidorov-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        mvc.perform(put("/api/v1/employees/" + id + "/dismissal").with(admin()))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/me").with(as(email, "employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee").doesNotExist());
    }

    @Test
    void aTokenWithoutRolesReadsNoCompanyData() throws Exception {
        mvc.perform(get("/api/v1/employees").with(as("nobody@corp.ru"))).andExpect(status().isForbidden());
    }
}
