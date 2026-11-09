package ai.costguard.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Secrets a company keeps in the core: encrypted, never returned, handed only to the connector agent (ADR 0030). */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionSecretsIntegrationTest extends PostgresTestSupport {

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry registry) {
        registry.add("costguard.connections.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "a@x.ru")
                .claim("realm_access", Map.of("roles", List.of(roles)))).authorities(realmRoles);
    }

    private static final String OKTA = """
            {"type":"okta","name":"Okta","settings":{"url":"https://romashka.okta.example"},
             "secrets":{"OKTA_TOKEN":"00aSecretOktaToken"}}""";

    private String connect(String body) throws Exception {
        String response = mvc.perform(post("/api/v1/connections").with(as(tenant, "it_admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("00aSecretOktaToken");
        return JsonPath.read(response, "$.id");
    }

    @Test
    void secretsAreKeptEncryptedAndOnlyTheirNamesAreShown() throws Exception {
        String id = connect(OKTA);

        String body = mvc.perform(get("/api/v1/connections/" + id).with(as(tenant, "owner")))
                .andExpect(jsonPath("$.secretsInCore[0]").value("OKTA_TOKEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("00aSecretOktaToken");
        try (Connection c = appDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("set app.tenant_id = '" + tenant + "'");
            try (ResultSet rs = s.executeQuery("select secrets from connection where id = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(new String(rs.getBytes(1), StandardCharsets.ISO_8859_1)).doesNotContain("SecretOkta");
            }
        }
    }

    @Test
    void onlyTheConnectorAgentGetsThem() throws Exception {
        connect(OKTA);

        mvc.perform(get("/api/v1/connections/agent-secrets").with(as(tenant, "connector")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.secrets.okta.OKTA_TOKEN").value("00aSecretOktaToken"));
        mvc.perform(get("/api/v1/connections/agent-secrets").with(as(tenant, "owner")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("connector_role_required"));
        mvc.perform(get("/api/v1/connections/agent-secrets").with(as(UUID.randomUUID(), "connector")))
                .andExpect(jsonPath("$.secrets.okta").doesNotExist());
    }

    @Test
    void anUnknownSecretIsRefusedAndNoneStaysWithTheAgent() throws Exception {
        mvc.perform(post("/api/v1/connections").with(as(tenant, "it_admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"okta","name":"Okta","settings":{"url":"https://o.example"},
                                 "secrets":{"ONEC_PASSWORD":"x"}}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("secret_unknown"));

        String id = connect("""
                {"type":"okta","name":"Okta","settings":{"url":"https://o.example"}}""");
        mvc.perform(get("/api/v1/connections/" + id).with(as(tenant, "owner")))
                .andExpect(jsonPath("$.secretsInCore.length()").value(0));
        mvc.perform(get("/api/v1/connections/agent-secrets").with(as(tenant, "connector")))
                .andExpect(jsonPath("$.secrets.okta").doesNotExist());
    }

    @Test
    void disconnectErasesThem() throws Exception {
        String id = connect(OKTA);

        mvc.perform(delete("/api/v1/connections/" + id).with(as(tenant, "owner"))).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/connections/" + id).with(as(tenant, "owner")))
                .andExpect(jsonPath("$.secretsInCore.length()").value(0));
        mvc.perform(get("/api/v1/connections/agent-secrets").with(as(tenant, "connector")))
                .andExpect(jsonPath("$.secrets.okta").doesNotExist());
    }
}
