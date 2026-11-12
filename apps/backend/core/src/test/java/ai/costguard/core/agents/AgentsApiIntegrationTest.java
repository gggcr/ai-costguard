package ai.costguard.core.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.platform.errors.ApiException;
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
@Import(AgentsApiIntegrationTest.States.class)
class AgentsApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class States {
        final List<AgentStateChanged> seen = new CopyOnWriteArrayList<>();
        final List<MandatePublished> published = new CopyOnWriteArrayList<>();

        @EventListener
        void on(AgentStateChanged event) {
            seen.add(event);
        }

        @EventListener
        void on(MandatePublished event) {
            published.add(event);
        }

        List<MandatePublished> published(String mandateId) {
            UUID id = UUID.fromString(mandateId);
            return published.stream().filter(e -> e.mandateId().equals(id)).toList();
        }

        AgentStateChanged last(String agentId) {
            UUID id = UUID.fromString(agentId);
            return seen.stream().filter(e -> e.agentId().equals(id)).reduce((a, b) -> b).orElseThrow();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired States states;
    @Autowired MandateNotarizations notarizations;

    private final UUID tenant = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String email, String... roles) {
        return token(tenantId, subject(tenantId, email), email, roles);
    }

    /** A token with its own subject: the person is found by the sub, the e-mail only rides along. */
    private RequestPostProcessor token(UUID tenantId, String sub, String email, String... roles) {
        return jwt()
                .jwt(j -> j.subject(sub).claim("tenant_id", tenantId.toString()).claim("email", email)
                        .claim("email_verified", false).claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as(tenant, "admin@x.ru", "it_admin");
    }

    /** Only the owner links a user to an employee (finding H1 of 2026-11-12). */
    private RequestPostProcessor owner() {
        return as(tenant, "owner@x.ru", "owner");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String mandateBody(String name, String hours) {
        return """
                {"name":"%s","models":["gpt-5","a\\"b"],"vendors":["OpenAI, Inc"],"maxPerOperation":500,
                 "approvalThreshold":300,"hours":"%s"}
                """.formatted(name, hours);
    }

    private String mandate() throws Exception {
        String body = mvc.perform(json(post("/api/v1/mandates"),
                        mandateBody(UUID.randomUUID().toString(), "09:00–21:00")).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String agentBody(String mandateId, UUID ownerId) {
        return """
                {"name":"Bot","description":"d","framework":"LangChain","departmentId":"%s","ownerId":"%s",
                 "mandateId":"%s"}
                """.formatted(UUID.randomUUID(), ownerId, mandateId);
    }

    private String agent(String mandateId) throws Exception {
        String body = mvc.perform(json(post("/api/v1/agents"), agentBody(mandateId, owner)).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** A user tied to an employee, so the ownership check has someone to find. */
    private UUID employeeWithLogin(String email) throws Exception {
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String employee = JsonPath.read(mvc.perform(json(post("/api/v1/employees"), """
                {"name":"O","email":"%s","departmentId":"%s","position":"P"}
                """.formatted(email, department)).with(admin())).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/users"), """
                {"name":"O","email":"%s","role":"agent_owner","employeeId":"%s"}
                """.formatted(email, employee)).with(owner())).andExpect(status().isCreated());
        // the person's Keycloak account, linked as at their first confirmed sign-in
        login(tenant, email, "agent_owner", UUID.fromString(employee));
        return UUID.fromString(employee);
    }

    /**
     * The e-mail spoofing finding of 2026-11-12: agent owner Q puts agent owner P's address on Q's Keycloak account.
     * Ownership follows the token's subject, so Q still owns only Q's agents.
     */
    @Test
    void anotherAgentOwnersEmailDoesNotMakeTheirAgentsMine() throws Exception {
        String mailP = "p-" + UUID.randomUUID() + "@x.ru";
        String mailQ = "q-" + UUID.randomUUID() + "@x.ru";
        UUID p = employeeWithLogin(mailP);
        UUID q = employeeWithLogin(mailQ);
        String mandate = mandate();
        String agentOfP = JsonPath.read(mvc.perform(json(post("/api/v1/agents"), agentBody(mandate, p)).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String agentOfQ = JsonPath.read(mvc.perform(json(post("/api/v1/agents"), agentBody(mandate, q)).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        RequestPostProcessor qAsP = token(tenant, subject(tenant, mailQ), mailP, "agent_owner");

        mvc.perform(put("/api/v1/agents/" + agentOfP + "/suspension").with(qAsP))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_agent_owner"));
        mvc.perform(get("/api/v1/agents/" + agentOfP).with(admin())).andExpect(jsonPath("$.status").value(
                org.hamcrest.Matchers.not("suspended")));
        // Q is still Q: their own agent, whatever the token's e-mail
        mvc.perform(put("/api/v1/agents/" + agentOfQ + "/suspension").with(qAsP)).andExpect(status().isOk());
        // someone without a CostGuard account and with P's address owns nothing
        mvc.perform(put("/api/v1/agents/" + agentOfP + "/suspension")
                        .with(token(tenant, "kc-nobody-" + UUID.randomUUID(), mailP, "agent_owner")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_agent_owner"));
        mvc.perform(put("/api/v1/agents/" + agentOfP + "/suspension").with(as(tenant, mailP, "agent_owner")))
                .andExpect(status().isOk());
    }

    @Test
    void theSecretIsShownOnceAndOnlyItsHashTravels() throws Exception {
        String agent = agent(mandate());

        String issued = mvc.perform(post("/api/v1/agents/" + agent + "/keys").with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(issued, "$.secret");

        assertThat(secret).startsWith("cg_live_");
        String listed = mvc.perform(get("/api/v1/agents/" + agent + "/keys").with(admin()))
                .andExpect(jsonPath("$", hasSize(1))).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(secret);
        assertThat(states.last(agent).virtualKeyHashes()).hasSize(1).doesNotContain(secret);
    }

    @Test
    void aRevokedKeyLeavesTheGatewayState() throws Exception {
        String agent = agent(mandate());
        String key = JsonPath.read(mvc.perform(post("/api/v1/agents/" + agent + "/keys").with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.key.id");

        mvc.perform(delete("/api/v1/agents/" + agent + "/keys/" + key).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/agents/" + agent + "/keys/" + key).with(admin())).andExpect(status().isNoContent());

        assertThat(states.last(agent).virtualKeyHashes()).isEmpty();
        mvc.perform(get("/api/v1/agents/" + agent + "/keys").with(admin()))
                .andExpect(jsonPath("$[0].status").value("revoked"));
    }

    @Test
    void aKeyOfAnotherAgentCannotBeRevokedThroughThisOne() throws Exception {
        String mandate = mandate();
        String first = agent(mandate);
        String second = agent(mandate);
        String key = JsonPath.read(mvc.perform(post("/api/v1/agents/" + first + "/keys").with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.key.id");

        mvc.perform(delete("/api/v1/agents/" + second + "/keys/" + key).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("key_not_found"));
    }

    @Test
    void suspensionReachesTheGatewayAndIsIdempotent() throws Exception {
        String agent = agent(mandate());

        mvc.perform(put("/api/v1/agents/" + agent + "/suspension").with(admin()))
                .andExpect(jsonPath("$.status").value("suspended"));
        int events = states.seen.size();
        mvc.perform(put("/api/v1/agents/" + agent + "/suspension").with(admin())).andExpect(status().isOk());

        assertThat(states.last(agent).suspended()).isTrue();
        assertThat(states.seen).hasSize(events);

        mvc.perform(delete("/api/v1/agents/" + agent + "/suspension").with(admin()))
                .andExpect(jsonPath("$.status").value("active"));
        assertThat(states.last(agent).suspended()).isFalse();
    }

    @Test
    void aDraftMandateGivesTheGatewayNothingToAllow() throws Exception {
        String mandate = mandate();
        String agent = agent(mandate);

        assertThat(states.last(agent).mandateExpression()).isNull();

        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()))
                .andExpect(jsonPath("$.status").value("published")).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()))
                .andExpect(jsonPath("$.version").value(1));

        AgentStateChanged state = states.last(agent);
        assertThat(state.mandateVersion()).isEqualTo(1);
        assertThat(state.mandateExpression()).isEqualTo("""
                (kind != "LLM_CALL" || model in ["gpt-5", "a\\"b"]) && vendor in ["OpenAI, Inc"] && cost <= 500.0 \
                && (time >= "09:00" && time < "21:00")""");
    }

    @Test
    void editingAPublishedMandateMakesItADraftAgain() throws Exception {
        String mandate = mandate();
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));

        mvc.perform(json(put("/api/v1/mandates/" + mandate), mandateBody(UUID.randomUUID().toString(), "круглосуточно"))
                        .with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void editingAMandateDoesNotCutOffRunningAgents() throws Exception {
        String mandate = mandate();
        String agent = agent(mandate);
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));
        String inForce = states.last(agent).mandateExpression();

        mvc.perform(json(put("/api/v1/mandates/" + mandate), mandateBody(UUID.randomUUID().toString(), "круглосуточно"))
                .with(admin())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/agents/" + agent + "/keys").with(admin())).andExpect(status().isCreated());

        assertThat(states.last(agent).mandateExpression()).isEqualTo(inForce);
        assertThat(states.last(agent).mandateVersion()).isEqualTo(1);

        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(states.last(agent).mandateExpression()).isNotEqualTo(inForce).doesNotContain("time");
    }

    @Test
    void aMandateInUseCannotBeDeleted() throws Exception {
        String mandate = mandate();
        String agent = agent(mandate);

        mvc.perform(delete("/api/v1/mandates/" + mandate).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("mandate_in_use"));
        mvc.perform(delete("/api/v1/agents/" + agent).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/mandates/" + mandate).with(admin())).andExpect(status().isNoContent());
    }

    @Test
    void deletingAnAgentCutsItOffAtTheGateway() throws Exception {
        String agent = agent(mandate());
        mvc.perform(post("/api/v1/agents/" + agent + "/keys").with(admin()));

        mvc.perform(delete("/api/v1/agents/" + agent).with(admin())).andExpect(status().isNoContent());

        assertThat(states.last(agent).suspended()).isTrue();
        assertThat(states.last(agent).virtualKeyHashes()).isEmpty();
    }

    @Test
    void anAgentOwnerChangesOnlyTheirOwnAgents() throws Exception {
        String email = UUID.randomUUID() + "@x.ru";
        UUID employee = employeeWithLogin(email);
        RequestPostProcessor agentOwner = as(tenant, email, "agent_owner");
        String mandate = mandate();
        String foreign = agent(mandate);
        String own = JsonPath.read(mvc.perform(json(post("/api/v1/agents"), agentBody(mandate, employee)).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(put("/api/v1/agents/" + foreign + "/suspension").with(agentOwner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_agent_owner"));
        mvc.perform(put("/api/v1/agents/" + own + "/suspension").with(agentOwner)).andExpect(status().isOk());
    }

    @Test
    void anAgentOwnerCannotHandAnAgentOver() throws Exception {
        String email = UUID.randomUUID() + "@x.ru";
        UUID employee = employeeWithLogin(email);
        String mandate = mandate();
        String own = JsonPath.read(mvc.perform(json(post("/api/v1/agents"), agentBody(mandate, employee)).with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(json(put("/api/v1/agents/" + own), agentBody(mandate, UUID.randomUUID()))
                        .with(as(tenant, email, "agent_owner")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMandateOfAnotherTenantCannotBeAttached() throws Exception {
        String foreign = mandate();
        UUID other = UUID.randomUUID();

        mvc.perform(json(post("/api/v1/agents"), agentBody(foreign, owner)).with(as(other, "a@x.ru", "owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("mandate_not_found"));
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        String agent = agent(mandate());
        UUID other = UUID.randomUUID();

        mvc.perform(get("/api/v1/agents/" + agent).with(as(other, "a@x.ru", "owner"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agents").with(as(other, "a@x.ru", "owner"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/v1/agents/" + agent + "/keys").with(as(other, "a@x.ru", "owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAuditorCannotChangeAnything() throws Exception {
        String agent = agent(mandate());

        mvc.perform(put("/api/v1/agents/" + agent + "/suspension").with(as(tenant, "a@x.ru", "auditor")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
    }

    @Test
    void aThresholdAboveTheLimitIsRejected() throws Exception {
        String body = mandateBody("x", "только рабочие дни").replace("\"approvalThreshold\":300", "\"approvalThreshold\":600");
        mvc.perform(json(post("/api/v1/mandates"), body).with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("threshold_above_limit"));
    }

    @Test
    void workdaysOnlyIsAccepted() throws Exception {
        mvc.perform(json(post("/api/v1/mandates"), mandateBody(UUID.randomUUID().toString(), "только рабочие дни"))
                .with(admin())).andExpect(status().isCreated());
    }

    @Test
    void everyPublishedVersionGoesToTheNotaryOnce() throws Exception {
        String mandate = mandate();
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));
        mvc.perform(json(put("/api/v1/mandates/" + mandate), mandateBody(UUID.randomUUID().toString(), "круглосуточно"))
                .with(admin()));
        mvc.perform(get("/api/v1/mandates/" + mandate).with(admin()))
                .andExpect(jsonPath("$.status").value("draft")).andExpect(jsonPath("$.mandateHash").isString());
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));

        List<MandatePublished> sent = states.published(mandate);
        assertThat(sent).extracting(MandatePublished::version).containsExactly(1, 2);
        assertThat(sent).allSatisfy(e -> {
            assertThat(e.tenantId()).isEqualTo(tenant);
            assertThat(e.mandateHash()).matches("[0-9a-f]{64}");
        });
        assertThat(sent.get(0).mandateHash()).isNotEqualTo(sent.get(1).mandateHash());
        mvc.perform(get("/api/v1/mandates/" + mandate).with(admin()))
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.mandateHash").value(sent.get(1).mandateHash()))
                .andExpect(jsonPath("$.nearTxHash").value(nullValue()));
    }

    @Test
    void theNotaryTransactionIsShownOnTheMandate() throws Exception {
        String mandate = mandate();
        mvc.perform(get("/api/v1/mandates/" + mandate).with(admin()))
                .andExpect(jsonPath("$.mandateHash").value(nullValue()));
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));
        MandatePublished v1 = states.published(mandate).getFirst();

        notarizations.notarized(tenant, v1.mandateId(), 1, v1.mandateHash(), "TX1", v1.publishedAt());
        notarizations.notarized(tenant, v1.mandateId(), 1, v1.mandateHash(), "TX-again", v1.publishedAt());

        mvc.perform(get("/api/v1/mandates/" + mandate).with(admin())).andExpect(jsonPath("$.nearTxHash").value("TX1"));
    }

    @Test
    void aHashTheCoreDidNotComputeIsRefused() throws Exception {
        String mandate = mandate();
        mvc.perform(put("/api/v1/mandates/" + mandate + "/publication").with(admin()));
        UUID id = UUID.fromString(mandate);

        assertThatThrownBy(() -> notarizations.notarized(tenant, id, 1, "f".repeat(64), "TX", java.time.Instant.now()))
                .isInstanceOf(ApiException.class).hasMessageContaining("did not compute");
        assertThatThrownBy(() -> notarizations.notarized(UUID.randomUUID(), id, 1, "f".repeat(64), "TX",
                java.time.Instant.now())).isInstanceOf(ApiException.class);
        mvc.perform(get("/api/v1/mandates/" + mandate).with(admin())).andExpect(jsonPath("$.nearTxHash").value(nullValue()));
    }

    @Test
    void badHoursAreRejected() throws Exception {
        mvc.perform(json(post("/api/v1/mandates"), mandateBody("x", "25:00–26:00")).with(admin()))
                .andExpect(status().isBadRequest());
    }
}
