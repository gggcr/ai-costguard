package ai.costguard.core.cabinet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** «Мои документы» (ADR 0052): the employer adds, the employee reads only their own and is told. */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentsIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email).claim("name", "Кадры")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor hr() {
        return as("hr@corp.ru", "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** An employee with the CostGuard account an onboarding or an invitation leaves, linked to their Keycloak sub. */
    private String employee(String email) throws Exception {
        String id = employeeRecord(email);
        login(tenant, email, "employee", UUID.fromString(id));
        return id;
    }

    private String employeeRecord(String email) throws Exception {
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                        "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(hr()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(json(post("/api/v1/employees"), """
                        {"name":"Иванов Иван","email":"%s","departmentId":"%s","position":"Разработчик"}
                        """.formatted(email, department)).with(hr()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String upload(String employeeId, String title) throws Exception {
        var file = new MockMultipartFile("file", "договор.pdf", "application/pdf",
                "%PDF-1.4 трудовой договор".getBytes(StandardCharsets.UTF_8));
        return JsonPath.read(mvc.perform(multipart("/api/v1/employees/{id}/documents", employeeId).file(file)
                        .param("kind", "employment_contract").param("title", title).param("number", "ТД-17")
                        .param("signedOn", "2026-03-01").with(hr()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theEmployeeSeesAndDownloadsTheirDocumentAndIsTold() throws Exception {
        String email = "ivanov-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        String doc = upload(id, "Трудовой договор");

        mvc.perform(get("/api/v1/me/documents").with(as(email, "employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(doc))
                .andExpect(jsonPath("$[0].title").value("Трудовой договор"))
                .andExpect(jsonPath("$[0].number").value("ТД-17"))
                .andExpect(jsonPath("$[0].source").value("upload"));
        byte[] file = mvc.perform(get("/api/v1/me/documents/{id}/file", doc).with(as(email, "employee")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, StandardCharsets.UTF_8)).contains("трудовой договор");

        // the notice is theirs: in their cabinet feed, not in the company's
        mvc.perform(get("/api/v1/me/notifications").with(as(email, "employee")))
                .andExpect(jsonPath("$.items[0].text").value("Новый документ: Трудовой договор"))
                .andExpect(jsonPath("$.unread").value(1));
        String feed = mvc.perform(get("/api/v1/notifications").with(hr())).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(feed).doesNotContain("Новый документ");
    }

    @Test
    void anotherEmployeeCannotReadIt() throws Exception {
        String mine = "a-%s@corp.ru".formatted(UUID.randomUUID());
        String other = "b-%s@corp.ru".formatted(UUID.randomUUID());
        String doc = upload(employee(mine), "Соглашение о неразглашении");
        employee(other);
        mvc.perform(get("/api/v1/me/documents/{id}/file", doc).with(as(other, "employee")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/documents").with(as(other, "employee")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anEmployeeCannotAddOrReadOthersDocuments() throws Exception {
        String email = "c-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        var file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[] {1});
        mvc.perform(multipart("/api/v1/employees/{id}/documents", id).file(file).param("kind", "other")
                .param("title", "x").with(as(email, "employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/employees/{id}/documents", id).with(as(email, "employee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHandoverActIsADocumentOfTheEmployee() throws Exception {
        String email = "d-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        String asset = JsonPath.read(mvc.perform(json(post("/api/v1/assets"), """
                        {"inventoryNumber":"INV-%s","model":"MacBook Pro","type":"LAPTOP","cost":180000.00}
                        """.formatted(UUID.randomUUID())).with(hr()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(id))
                .with(hr())).andExpect(status().isOk());

        String act = JsonPath.read(mvc.perform(get("/api/v1/me/documents").with(as(email, "employee")))
                .andExpect(jsonPath("$[0].source").value("asset_act"))
                .andExpect(jsonPath("$[0].title").value(org.hamcrest.Matchers.startsWith("Акт выдачи: MacBook Pro")))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(get("/api/v1/me/documents/{id}/file", act).with(as(email, "employee")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        // the listener runs after the issue commits
        long until = System.currentTimeMillis() + 10_000;
        String feed = "";
        while (System.currentTimeMillis() < until && !feed.contains("Вам выдана техника: MacBook Pro")) {
            feed = mvc.perform(get("/api/v1/me/notifications").with(as(email, "employee"))).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);
            Thread.sleep(100);
        }
        assertThat(feed).contains("Вам выдана техника: MacBook Pro").contains("в разделе «Документы»");
    }

    @Test
    void aDocumentAddedByMistakeIsRemoved() throws Exception {
        String email = "e-%s@corp.ru".formatted(UUID.randomUUID());
        String id = employee(email);
        String doc = upload(id, "Не тот файл");
        mvc.perform(delete("/api/v1/employees/{e}/documents/{d}", id, doc).with(hr()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/documents").with(as(email, "employee")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
