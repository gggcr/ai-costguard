package ai.costguard.core.policies;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(PoliciesApiIntegrationTest.Snapshots.class)
class PoliciesApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Snapshots {
        final List<PolicySnapshotPublished> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(PolicySnapshotPublished event) {
            seen.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Snapshots snapshots;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as(tenant, "it_admin");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String rule(String name, String expression, String effect, boolean enabled) {
        return """
                {"name":"%s","expression":"%s","effect":"%s","enabled":%s}
                """.formatted(name, expression.replace("\"", "\\\""), effect, enabled);
    }

    private String create(String name, String expression, String effect) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/policies/rules"), rule(name, expression, effect, true))
                .with(admin())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static final String OPERATIONS = """
            "operations":[
              {"agentId":"a","kind":"LLM_CALL","vendor":"OpenAI","model":"gpt-5","cost":10,"time":"12:00","weekday":2},
              {"agentId":"a","kind":"PAID_API","vendor":"SPARK","cost":5000,"time":"12:00","weekday":2},
              {"agentId":"a","kind":"LLM_CALL","vendor":"OpenAI","model":"gpt-5","cost":10,"time":"03:00","weekday":6}
            ]""";

    @Test
    void anInvalidExpressionNeverGetsSaved() throws Exception {
        for (String bad : List.of("cost >", "unknown_var > 1", "cost + 1")) {
            mvc.perform(json(post("/api/v1/policies/rules"), rule("r" + UUID.randomUUID(), bad, "DENY", true))
                            .with(admin()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_expression"));
        }
    }

    @Test
    void simulationAppliesTheStrongestEffect() throws Exception {
        create("night", "weekday > 5 || time < \"06:00\"", "DENY");
        create("expensive", "cost > 1000.0", "REQUIRE_APPROVAL");
        create("expensive weekend", "cost > 1.0 && weekday > 5", "REQUIRE_APPROVAL");

        mvc.perform(json(post("/api/v1/policies/simulation"), "{" + OPERATIONS + "}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.denied").value(1))
                .andExpect(jsonPath("$.requireApproval").value(1))
                .andExpect(jsonPath("$.operations[0].outcome").value("ALLOW"))
                .andExpect(jsonPath("$.operations[1].outcome").value("REQUIRE_APPROVAL"))
                .andExpect(jsonPath("$.operations[2].outcome").value("DENY"))
                .andExpect(jsonPath("$.operations[2].matchedRules", hasSize(2)));
    }

    @Test
    void draftRulesCanBeTriedWithoutSavingThem() throws Exception {
        String body = "{\"rules\":[" + rule("only openai", "vendor != \"OpenAI\"", "DENY", true) + "]," + OPERATIONS + "}";

        mvc.perform(json(post("/api/v1/policies/simulation"), body).with(as(tenant, "finance")))
                .andExpect(jsonPath("$.denied").value(1));
        mvc.perform(get("/api/v1/policies/rules").with(admin())).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void publicationCarriesOnlyEnabledRulesAndCountsVersions() throws Exception {
        create("on", "cost > 1.0", "DENY");
        mvc.perform(json(post("/api/v1/policies/rules"), rule("off", "cost > 2.0", "DENY", false)).with(admin()))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/policies/publication").with(admin())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/policies/publication").with(admin())).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/policies/publication").with(admin())).andExpect(jsonPath("$.version").value(2));

        PolicySnapshotPublished last = snapshots.seen.stream().filter(s -> s.tenantId().equals(tenant))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(last.version()).isEqualTo(2);
        assertThat(last.rules()).extracting(PolicySnapshotPublished.Rule::name).containsExactly("on");
    }

    @Test
    void removingTheLastRulePublishesAnEmptySnapshot() throws Exception {
        String id = create("only", "cost > 1.0", "DENY");
        mvc.perform(delete("/api/v1/policies/rules/" + id).with(admin())).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/policies/publication").with(admin())).andExpect(status().isOk());

        PolicySnapshotPublished last = snapshots.seen.stream().filter(s -> s.tenantId().equals(tenant))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(last.rules()).isEmpty();
    }

    @Test
    void updatingChecksTheExpressionToo() throws Exception {
        String id = create("r", "cost > 1.0", "DENY");

        mvc.perform(json(put("/api/v1/policies/rules/" + id), rule("r", "cost >", "DENY", true)).with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void financeCanSimulateButNotChange() throws Exception {
        mvc.perform(json(post("/api/v1/policies/rules"), rule("x", "cost > 1.0", "DENY", true)).with(as(tenant, "finance")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
        mvc.perform(post("/api/v1/policies/publication").with(as(tenant, "finance"))).andExpect(status().isForbidden());
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        String id = create("mine", "cost > 1.0", "DENY");
        UUID other = UUID.randomUUID();

        mvc.perform(get("/api/v1/policies/rules").with(as(other, "owner"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/v1/policies/rules/" + id).with(as(other, "owner"))).andExpect(status().isNotFound());
    }
}
