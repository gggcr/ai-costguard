package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Вход через Microsoft 365 (ADR 0058), with Keycloak in memory. Shares the registration test's context. */
@SpringBootTest(properties = {"costguard.registration.rate-limit.per-ip=4",
        "costguard.registration.rate-limit.per-email=3"})
@AutoConfigureMockMvc
@Import(RegistrationIntegrationTest.Fakes.class)
class CompanyLoginIntegrationTest extends PostgresTestSupport {

    static final FakeIdentityProvider ACCOUNTS = RegistrationIntegrationTest.ACCOUNTS;

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String domain = "corp-" + UUID.randomUUID().toString().substring(0, 8) + ".example";

    private RequestPostProcessor as(UUID company, String role) {
        return jwt().jwt(j -> j.claim("tenant_id", company.toString()).claim("email", "boss@" + domain)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private void someoneAt(UUID company, String emailDomain) {
        JdbcClient.create(superuserAppDataSource())
                .sql("insert into user_account (id, tenant_id, name, email, role) values (?, ?, 'x', ?, 'owner')")
                .params(UUID.randomUUID(), company, "boss-" + UUID.randomUUID() + "@" + emailDomain).update();
    }

    private ResultActions save(UUID company, String role, String secret, String... domains) throws Exception {
        String list = String.join("\",\"", domains);
        String body = """
                {"directoryId":"11111111-2222-3333-4444-555555555555","clientId":"app-1",%s"domains":["%s"]}
                """.formatted(secret == null ? "" : "\"clientSecret\":\"" + secret + "\",", list);
        return mvc.perform(put("/api/v1/company/login").with(as(company, role)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void anOwnerConnectsMicrosoftAndTheWorkEmailLeadsToIt() throws Exception {
        someoneAt(tenant, domain);
        String alias = "ms-" + tenant;
        mvc.perform(get("/api/v1/company/login").with(as(tenant, "owner")))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.redirectUri").value(org.hamcrest.Matchers.endsWith(
                        "/broker/" + alias + "/endpoint")));
        save(tenant, "owner", null, domain).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("secret_required"));
        save(tenant, "owner", "s3cret", domain.toUpperCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.domains[0]").value(domain))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
        assertThat(ACCOUNTS.logins.get(alias).clientSecret()).isEqualTo("s3cret");
        // a change without the secret keeps it
        save(tenant, "owner", null, domain).andExpect(status().isOk());
        assertThat(ACCOUNTS.logins.get(alias).clientSecret()).isEqualTo("s3cret");

        // before signing in, nobody is authenticated
        mvc.perform(get("/api/v1/login/hint").param("email", "Anna@" + domain.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("microsoft"))
                .andExpect(jsonPath("$.alias").value(alias));
        mvc.perform(get("/api/v1/login/hint").param("email", "anna@elsewhere.example"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/company/login").with(as(tenant, "owner"))).andExpect(status().isNoContent());
        assertThat(ACCOUNTS.logins).doesNotContainKey(alias);
        mvc.perform(get("/api/v1/login/hint").param("email", "anna@" + domain)).andExpect(status().isNotFound());
    }

    @Test
    void aDomainBelongsToOneCompanyAndOnlyToItsOwnPeople() throws Exception {
        someoneAt(tenant, domain);
        save(tenant, "owner", "s", "gmail.com").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("domain_not_ours"));
        save(tenant, "owner", "s", domain).andExpect(status().isOk());

        UUID other = UUID.randomUUID();
        someoneAt(other, domain);
        save(other, "owner", "s", domain).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("domain_taken"));
    }

    @Test
    void onlyAnOwnerConnectsIt() throws Exception {
        someoneAt(tenant, domain);
        save(tenant, "finance", "s", domain).andExpect(status().isForbidden());
        save(tenant, "employee", "s", domain).andExpect(status().isForbidden());
    }

    @Test
    void keycloakAwayIsAProblemNotAnError() throws Exception {
        someoneAt(tenant, domain);
        ACCOUNTS.configured = false;
        try {
            save(tenant, "owner", "s", domain).andExpect(status().isServiceUnavailable());
        } finally {
            ACCOUNTS.configured = true;
        }
    }
}
