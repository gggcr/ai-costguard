package ai.costguard.core.activity;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Who the activity log says acted (audit of 2026-11-13). The log is the company's record of who changed what; a
 * person can change the name and e-mail of their Keycloak account, so a person with a CostGuard account is logged as
 * the company stored that account, by the token's subject, and not as the token names them, now or retroactively.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityActorIdentityIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());
    private final UUID tenant = UUID.randomUUID();
    private final String subject = "kc-" + UUID.randomUUID();

    private RequestPostProcessor as(String sub, String email, String name, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenant.toString()).claim("email", email)
                .claim("name", name).claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private RequestPostProcessor auditor() {
        return as("kc-auditor-" + tenant, "audit@romashka.example", "Аудитор", "auditor");
    }

    @BeforeEach
    void account() throws Exception {
        mvc.perform(put("/api/v1/company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ООО «Ромашка»\",\"currency\":\"RUB\"}")
                        .with(as("kc-setup-" + tenant, "setup@romashka.example", "Настройка", "owner")))
                .andExpect(status().is2xxSuccessful());
        su.sql("""
                insert into user_account (id, tenant_id, name, email, role, keycloak_subject)
                values (?, ?, 'Анна Владелец', 'anna@romashka.example', 'owner', ?)""")
                .params(UUID.randomUUID(), tenant, subject).update();
    }

    private String budget(RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"B-%s\",\"level\":\"company\",\"limit\":1000,\"currency\":\"RUB\"}"
                                .formatted(UUID.randomUUID()))
                        .with(who))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void anAccountIsLoggedAsTheCompanyStoredItNotAsTheTokenNamesIt() throws Exception {
        String budget = budget(as(subject, "ceo@romashka.example", "Генеральный директор", "owner"));

        mvc.perform(get("/api/v1/activity").param("targetId", budget).with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("budget.created"))
                .andExpect(jsonPath("$[0].actor.name").value("Анна Владелец"))
                .andExpect(jsonPath("$[0].actor.email").value("anna@romashka.example"));
    }

    @Test
    void aLaterTokenDoesNotRenameWhatThePersonDidBefore() throws Exception {
        String budget = budget(as(subject, "anna@romashka.example", "Анна Владелец", "owner"));
        mvc.perform(put("/api/v1/budgets/{id}/limit", budget).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":2500}").with(as(subject, "petrov@romashka.example", "Петров Иван", "owner")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/activity").param("targetId", budget).with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("budget.limit_changed"))
                .andExpect(jsonPath("$[0].actor.name").value("Анна Владелец"))
                .andExpect(jsonPath("$[1].action").value("budget.created"))
                .andExpect(jsonPath("$[1].actor.name").value("Анна Владелец"))
                .andExpect(jsonPath("$[1].actor.email").value("anna@romashka.example"));
    }
}
