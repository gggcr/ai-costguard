package ai.costguard.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Who moved a finding and who uploaded a file (audit of 2026-11-13). Neither leaves an activity log entry, so the
 * address stored with them is the only record of who did it; for a person with a CostGuard account it is the address
 * the company stored for the account, found by the token's subject, not the e-mail the token carries (a person can
 * change that one in their Keycloak account). A token without an account keeps its e-mail, or its subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenAttributionIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired FindingsIntake intake;

    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());
    private final UUID tenant = UUID.randomUUID();
    private final String subject = "kc-" + UUID.randomUUID();

    private RequestPostProcessor as(String sub, String email, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenant.toString()).claim("email", email)
                .claim("name", "Кто угодно").claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    /** The finance person with an account, signed in with a token that names someone else. */
    private RequestPostProcessor spoofing() {
        return as(subject, "ceo@romashka.example", "finance");
    }

    @BeforeEach
    void account() throws Exception {
        mvc.perform(put("/api/v1/company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ООО «Ромашка»\",\"currency\":\"RUB\"}")
                        .with(as("kc-setup-" + tenant, "setup@romashka.example", "owner")))
                .andExpect(status().is2xxSuccessful());
        su.sql("""
                insert into user_account (id, tenant_id, name, email, role, keycloak_subject)
                values (?, ?, 'Анна Бухгалтер', 'anna@romashka.example', 'finance', ?)""")
                .params(UUID.randomUUID(), tenant, subject).update();
    }

    @Test
    void aFindingIsMovedByTheAccountNotByTheTokenEmail() throws Exception {
        UUID id = intake.detected(tenant, "fp-" + UUID.randomUUID(), "unused_licenses", "Jira: 4 seats unused", "d",
                new BigDecimal("3160"), null, null);

        mvc.perform(post("/api/v1/findings/{id}/actions", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()))
                        .with(spoofing()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[-1].by").value("anna@romashka.example"));
    }

    @Test
    void aFileIsUploadedByTheAccountNotByTheTokenEmail() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.csv", "text/csv",
                ("Vendor;Sum\nАкме;" + UUID.randomUUID().hashCode() + "\n").getBytes());
        String id = JsonPath.read(mvc.perform(multipart("/api/v1/imports").file(file).with(spoofing()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/v1/imports/{id}", id).with(spoofing()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedBy").value("anna@romashka.example"));
    }

    @Test
    void aTokenWithoutAnAccountKeepsItsEmail() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.csv", "text/csv",
                ("Vendor;Sum\nАкме;" + UUID.randomUUID().hashCode() + "\n").getBytes());
        RequestPostProcessor connector = as("kc-none-" + tenant, "robot@romashka.example", "finance");
        String id = JsonPath.read(mvc.perform(multipart("/api/v1/imports").file(file).with(connector))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/v1/imports/{id}", id).with(connector))
                .andExpect(jsonPath("$.uploadedBy").value("robot@romashka.example"));
    }
}
