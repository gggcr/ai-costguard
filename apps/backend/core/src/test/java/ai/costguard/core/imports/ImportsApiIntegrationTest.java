package ai.costguard.core.imports;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ImportsApiIntegrationTest.Events.class)
class ImportsApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Events {
        final List<ImportFileUploaded> uploaded = new CopyOnWriteArrayList<>();
        final List<ImportAccepted> accepted = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ImportFileUploaded e) {
            uploaded.add(e);
        }

        @EventListener
        void on(ImportAccepted e) {
            accepted.add(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "u@x.ru")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Autowired Events events;
    @Autowired ImportResults results;

    private String upload(String name, String content, int expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/csv", content.getBytes());
        return JsonPath.read(mvc.perform(multipart("/api/v1/imports").file(file).with(as(tenant, "finance")))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void parsed(String id, List<ImportResults.Uncertain> uncertain) {
        results.parsed(tenant, UUID.fromString(id), List.of("Vendor", "Sum"), Map.of("Vendor", "vendor", "Sum", "amount"),
                2, new BigDecimal("1500"), List.of(new ImportResults.RowError(3, "no date")), uncertain);
    }

    @Test
    void theSameFileIsImportedOnce() throws Exception {
        String content = "Vendor,Sum\nZoom,1500\n" + UUID.randomUUID();

        String first = upload("invoices.csv", content, 201);
        String again = upload("renamed.csv", content, 200);

        assertThat(again).isEqualTo(first);
        assertThat(events.uploaded.stream().filter(e -> e.fileId().toString().equals(first))).hasSize(1);
    }

    @Test
    void unsupportedAndEmptyFilesAreRefused() throws Exception {
        MockMultipartFile exe = new MockMultipartFile("file", "x.exe", "application/octet-stream", new byte[] {1});
        mvc.perform(multipart("/api/v1/imports").file(exe).with(as(tenant, "finance")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unsupported_file"));
        MockMultipartFile empty = new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]);
        mvc.perform(multipart("/api/v1/imports").file(empty).with(as(tenant, "finance")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("empty_file"));
    }

    @Test
    void mappingThenReviewThenAccepted() throws Exception {
        String id = upload("a.csv", "x" + UUID.randomUUID(), 201);
        parsed(id, List.of(new ImportResults.Uncertain(2, "amount", "1 5OO", 0.4)));

        mvc.perform(json(put("/api/v1/imports/" + id + "/mapping"), "{\"mapping\":{\"Vendor\":\"nonsense\"}}")
                .with(as(tenant, "finance"))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/imports/" + id + "/mapping"),
                        "{\"mapping\":{\"Vendor\":\"vendor\",\"Sum\":\"amount\"},\"saveTemplate\":true}")
                        .with(as(tenant, "finance")))
                .andExpect(jsonPath("$.status").value("review")).andExpect(jsonPath("$.errors[0].row").value(3));
        String task = JsonPath.read(mvc.perform(get("/api/v1/imports/" + id).with(as(tenant, "finance")))
                .andReturn().getResponse().getContentAsString(), "$.reviewTasks[0].id");

        mvc.perform(json(put("/api/v1/imports/" + id + "/review-tasks/" + task), "{\"value\":\"1500\"}")
                .with(as(tenant, "finance"))).andExpect(jsonPath("$.status").value("done"));

        ImportAccepted accepted = events.accepted.stream().filter(e -> e.fileId().toString().equals(id)).findFirst()
                .orElseThrow();
        assertThat(accepted.corrections()).containsEntry(2, Map.of("amount", "1500"));
    }

    @Test
    void withoutReviewTasksTheMappingFinishesTheImport() throws Exception {
        String id = upload("b.csv", "y" + UUID.randomUUID(), 201);
        parsed(id, List.of());

        mvc.perform(json(put("/api/v1/imports/" + id + "/mapping"), "{\"mapping\":{\"Sum\":\"amount\"}}")
                .with(as(tenant, "finance"))).andExpect(jsonPath("$.status").value("done"));
        mvc.perform(json(put("/api/v1/imports/" + id + "/mapping"), "{\"mapping\":{\"Sum\":\"amount\"}}")
                .with(as(tenant, "finance"))).andExpect(status().isConflict());
    }

    @Test
    void tenantsAreIsolatedAndAuditorsCannotUpload() throws Exception {
        String id = upload("c.csv", "z" + UUID.randomUUID(), 201);

        mvc.perform(get("/api/v1/imports/" + id).with(as(UUID.randomUUID(), "owner"))).andExpect(status().isNotFound());
        MockMultipartFile file = new MockMultipartFile("file", "d.csv", "text/csv", "q".getBytes());
        mvc.perform(multipart("/api/v1/imports").file(file).with(as(tenant, "auditor"))).andExpect(status().isForbidden());
    }
}
