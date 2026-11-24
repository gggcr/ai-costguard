package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.Language;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.platform.tenant.TenantSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** The detector's texts follow the language each company set, read past RLS for its own tenant only (ADR 0074). */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyLanguagesIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired TenantSession session;
    @Autowired EmployeeDirectory company;
    @Autowired PlatformTransactionManager transactions;

    private void company(UUID tenant, String body) throws Exception {
        mvc.perform(put("/api/v1/company").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(j -> j.subject(subject(tenant, "o@corp.example")).claim("tenant_id", tenant.toString())
                        .claim("email", "o@corp.example").claim("realm_access", Map.of("roles", List.of("owner"))))
                        .authorities(realmRoles)))
                .andExpect(status().isOk());
    }

    @Test
    void eachCompanyGetsItsOwnLanguage() throws Exception {
        UUID english = UUID.randomUUID();
        UUID russian = UUID.randomUUID();
        UUID unset = UUID.randomUUID();
        company(english, "{\"name\":\"Acme Inc\",\"currency\":\"USD\",\"language\":\"en\"}");
        company(russian, "{\"name\":\"ООО Ромашка\",\"currency\":\"RUB\",\"language\":\"ru\"}");
        CompanyLanguages languages = new CompanyLanguages(session, company, transactions);

        assertThat(languages.apply(english)).isEqualTo(Language.EN);
        assertThat(languages.apply(russian)).isEqualTo(Language.RU);
        assertThat(languages.apply(unset)).as("a company without settings").isEqualTo(Language.RU);
    }
}
