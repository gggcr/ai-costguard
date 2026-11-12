package ai.costguard.core.cabinet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Теневые траты (ADR 0057): legal and anonymous; management sees what at least three people pay for. */
@SpringBootTest
@AutoConfigureMockMvc
class ShadowSpendIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private String department;

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post(path), body).with(as("o@corp.ru", "owner")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String person() throws Exception {
        if (department == null) {
            department = create("/api/v1/departments", "{\"name\":\"Дизайн-%s\"}".formatted(UUID.randomUUID()));
        }
        String email = "p-" + UUID.randomUUID() + "@corp.ru";
        String id = create("/api/v1/employees", "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, email, department));
        // the CostGuard account an onboarding or an invitation leaves, linked to the person's Keycloak sub
        login(tenant, email, "employee", UUID.fromString(id));
        return email;
    }

    private void report(String email, String service, int amount) throws Exception {
        mvc.perform(json(post("/api/v1/me/shadow-spend"),
                        "{\"service\":\"%s\",\"amount\":%d,\"paidOn\":\"2026-11-01\",\"purpose\":\"макеты\"}"
                                .formatted(service, amount)).with(as(email, "employee")))
                .andExpect(status().isCreated());
    }

    @Test
    void managementSeesAServiceOnlyWhenThreePeoplePayForIt() throws Exception {
        String a = person();
        String b = person();
        String c = person();
        report(a, "ChatGPT Plus", 2000);
        report(a, "ChatGPT Plus", 2000);
        report(b, "ChatGPT Plus", 2000);
        report(a, "Figma", 1500);
        mvc.perform(get("/api/v1/shadow-spend/summary").with(as("f@corp.ru", "finance")))
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.hiddenServices").value(2));

        report(c, "chatgpt plus", 1800);
        String summary = mvc.perform(get("/api/v1/shadow-spend/summary").with(as("f@corp.ru", "finance")))
                .andExpect(jsonPath("$.services[0].service").value("ChatGPT Plus"))
                .andExpect(jsonPath("$.services[0].category").value("ИИ"))
                .andExpect(jsonPath("$.services[0].people").value(3))
                .andExpect(jsonPath("$.services[0].reports").value(4))
                .andExpect(jsonPath("$.services[0].total").value(7800.00))
                .andExpect(jsonPath("$.services[0].departments[0].people").value(3))
                .andExpect(jsonPath("$.hiddenServices").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // no names and no e-mails anywhere in it
        assertThat(summary).doesNotContain(a).doesNotContain(b).doesNotContain(c).doesNotContain("макеты");
    }

    @Test
    void theRowNamesNobody() throws Exception {
        String a = person();
        report(a, "Notion", 800);
        List<Map<String, Object>> rows = JdbcClient.create(superuserAppDataSource())
                .sql("select * from shadow_spend where tenant_id = ?").param(tenant).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toString()).doesNotContain(a);
        assertThat(rows.get(0)).doesNotContainKey("employee_id");
        assertThat(rows.get(0).get("category")).isEqualTo("Документы и заметки");
    }

    @Test
    void theAuthorSeesAndDeletesOnlyTheirOwn() throws Exception {
        String a = person();
        String b = person();
        report(a, "Midjourney", 3000);
        String id = JsonPath.read(mvc.perform(get("/api/v1/me/shadow-spend").with(as(a, "employee")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("ИИ"))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(get("/api/v1/me/shadow-spend").with(as(b, "employee"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/v1/me/shadow-spend/{id}", id).with(as(b, "employee"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/shadow-spend/{id}", id).with(as(a, "employee"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/shadow-spend/summary").with(as(a, "employee"))).andExpect(status().isForbidden());
    }

    @Test
    void severalPurchasesComeInASheet() throws Exception {
        String a = person();
        String csv = """
                Сервис;Сумма;Дата;Назначение
                Canva Pro;999;01.10.2026;баннеры
                Zoom;1290,50;2026-10-15;
                ;100;01.10.2026;
                Loom;сто;01.10.2026;
                """;
        mvc.perform(multipart("/api/v1/me/shadow-spend/imports").file(new MockMultipartFile("file", "траты.csv",
                        "text/csv", csv.getBytes(StandardCharsets.UTF_8))).with(as(a, "employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(2));
        mvc.perform(get("/api/v1/me/shadow-spend").with(as(a, "employee")))
                .andExpect(jsonPath("$[?(@.service == 'Canva Pro')].category").value(
                        org.hamcrest.Matchers.hasItem("Дизайн")))
                .andExpect(jsonPath("$[?(@.service == 'Zoom')].category").value(
                        org.hamcrest.Matchers.hasItem("Общение и встречи")));
    }
}
