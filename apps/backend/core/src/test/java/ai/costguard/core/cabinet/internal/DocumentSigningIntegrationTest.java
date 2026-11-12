package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Подписание документов простой электронной подписью (ADR 0068). */
@SpringBootTest(properties = "costguard.connections.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
class DocumentSigningIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    /** The employer and the accountant are CostGuard users: their signing keys belong to their accounts. */
    @BeforeEach
    void theEmployerAndTheAccountantHaveAccounts() {
        login(tenant, "boss@corp.ru", "owner", null);
        login(tenant, "acc@corp.ru", "finance", null);
    }

    private RequestPostProcessor owner() {
        return as("boss@corp.ru", "owner");
    }

    private RequestPostProcessor finance() {
        return as("acc@corp.ru", "finance");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post(path), body).with(owner()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** An employee with the CostGuard account an onboarding or an invitation leaves, linked to their Keycloak sub. */
    private String employee(String email) throws Exception {
        String id = employeeRecord(email);
        login(tenant, email, "employee", UUID.fromString(id));
        return id;
    }

    private String employeeRecord(String email) throws Exception {
        String department = create("/api/v1/departments", "{\"name\":\"%s\"}".formatted(UUID.randomUUID()));
        return create("/api/v1/employees", "{\"name\":\"Иванов Иван\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, department));
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String upload(String employeeId, byte[] content, String name, String... signers) throws Exception {
        var request = multipart("/api/v1/employees/{id}/documents", employeeId)
                .file(new MockMultipartFile("file", name, "application/pdf", content))
                .param("kind", "agreement").param("title", "Дополнительное соглашение").param("signers", signers);
        return JsonPath.read(mvc.perform(request.with(owner())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static long step() {
        return Instant.now().getEpochSecond() / Totp.STEP_SECONDS;
    }

    @Test
    void theThreeSidesSignAndTheFileCarriesTheStamps() throws Exception {
        String mail = "ivan-" + UUID.randomUUID() + "@corp.ru";
        String ivan = employee(mail);
        String doc = upload(ivan, pdf(), "agreement.pdf", "employee", "employer", "accountant");

        // no key yet
        mvc.perform(json(post("/api/v1/me/documents/{id}/signature", doc), "{\"code\":\"123456\"}")
                .with(as(mail, "employee"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("signing_key_missing"));

        // the employee sets up an authenticator app
        String secret = JsonPath.read(mvc.perform(post("/api/v1/me/signing-key/totp").with(as(mail, "employee")))
                .andExpect(jsonPath("$.uri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/CostGuard")))
                .andReturn().getResponse().getContentAsString(), "$.secret");
        mvc.perform(json(put("/api/v1/me/signing-key/totp"), "{\"code\":\"%s\"}".formatted(Totp.code(secret, step())))
                .with(as(mail, "employee"))).andExpect(status().isOk()).andExpect(jsonPath("$.method").value("totp"));
        // the code used to confirm cannot sign
        mvc.perform(json(post("/api/v1/me/documents/{id}/signature", doc),
                "{\"code\":\"%s\"}".formatted(Totp.code(secret, step()))).with(as(mail, "employee")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("signing_code_wrong"));
        mvc.perform(json(post("/api/v1/me/documents/{id}/signature", doc),
                "{\"code\":\"%s\"}".formatted(Totp.code(secret, step() + 1))).with(as(mail, "employee")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signatures[0].party").value("employee"))
                .andExpect(jsonPath("$.signatures[0].method").value("totp"));

        // the employer signs with a PIN
        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"111111\"}").with(owner()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("signing_pin_weak"));
        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"482915\"}").with(owner()))
                .andExpect(jsonPath("$.method").value("pin"));
        mvc.perform(get("/api/v1/documents/awaiting-signature").with(owner()))
                .andExpect(jsonPath("$[?(@.id == '%s')].party".formatted(doc)).value(org.hamcrest.Matchers.hasItem("employer")));
        mvc.perform(json(post("/api/v1/documents/{id}/signature/employer", doc), "{\"code\":\"482915\"}")
                .with(finance())).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/documents/{id}/signature/employer", doc), "{\"code\":\"482915\"}")
                .with(owner())).andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/documents/{id}/signature/employer", doc), "{\"code\":\"482915\"}")
                .with(owner())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sign_already"));

        // the accountant
        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"730194\"}").with(finance()));
        mvc.perform(json(post("/api/v1/documents/{id}/signature/accountant", doc), "{\"code\":\"730194\"}")
                .with(finance())).andExpect(status().isOk()).andExpect(jsonPath("$.signatures.length()").value(3));
        mvc.perform(get("/api/v1/me/notifications").with(as(mail, "employee")))
                .andExpect(jsonPath("$.items[0].text").value("Документ подписан всеми сторонами: Дополнительное соглашение"));

        byte[] signed = mvc.perform(get("/api/v1/me/documents/{id}/file", doc).with(as(mail, "employee")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument d = Loader.loadPDF(signed)) {
            assertThat(d.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(d);
            assertThat(text).contains("Подписано электронной подписью").contains("ДОКУМЕНТ ПОДПИСАН ПРОСТОЙ ЭЛЕКТРОННОЙ ПОДПИСЬЮ")
                    .contains("Работодатель").contains("Бухгалтерия").contains("Лист электронных подписей");
        }
        mvc.perform(delete("/api/v1/employees/{e}/documents/{id}", ivan, doc).with(owner()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("document_signed"));
    }

    @Test
    void onlyAPdfIsSentForSigningAndFiveWrongCodesLock() throws Exception {
        String mail = "olga-" + UUID.randomUUID() + "@corp.ru";
        String olga = employee(mail);
        mvc.perform(multipart("/api/v1/employees/{id}/documents", olga)
                        .file(new MockMultipartFile("file", "a.docx", "application/octet-stream", new byte[] {1, 2, 3}))
                        .param("kind", "agreement").param("title", "x").param("signers", "employee").with(owner()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("sign_only_pdf"));
        String doc = upload(olga, pdf(), "a.pdf", "employee");
        // not asked: the employer is not a signer of this one
        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"482915\"}").with(owner()));
        mvc.perform(json(post("/api/v1/documents/{id}/signature/employer", doc), "{\"code\":\"482915\"}")
                .with(owner())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sign_not_asked"));

        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"905172\"}").with(as(mail, "employee")));
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/v1/me/documents/{id}/signature", doc), "{\"code\":\"000000\"}")
                    .with(as(mail, "employee"))).andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/me/documents/{id}/signature", doc), "{\"code\":\"905172\"}")
                .with(as(mail, "employee"))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("signing_locked"));
        // changing the PIN needs the current one
        mvc.perform(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"318264\"}").with(as(mail, "employee")))
                .andExpect(status().is4xxClientError());
    }
}
