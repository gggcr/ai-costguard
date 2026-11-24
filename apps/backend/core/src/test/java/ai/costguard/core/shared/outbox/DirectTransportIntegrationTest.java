package ai.costguard.core.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.OperationKind;
import ai.costguard.events.wire.SingleObject;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The core of an install without Kafka (ADR 0080): what the gateway needs reaches gateway_feed, the feed and the
 * operations inbox answer only the internal token, and an operation posted there is charged as from Kafka.
 */
@SpringBootTest(properties = {"costguard.events.transport=direct",
        "costguard.events.internal-token=" + DirectTransportIntegrationTest.TOKEN})
@AutoConfigureMockMvc
class DirectTransportIntegrationTest extends PostgresTestSupport {

    static final String TOKEN = "test-internal-token-0123456789abcdef-0123";

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired GatewayFeed feed;
    @Autowired TransactionTemplate tx;

    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private String budget(String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(as("finance"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");
    }

    private List<Map<String, Object>> items(long after) throws Exception {
        String body = mvc.perform(get("/internal/v1/gateway/feed").param("after", Long.toString(after))
                        .param("limit", "1000").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items");
    }

    private Map<String, Object> waitFor(String topic, String key) throws Exception {
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until) {
            for (Map<String, Object> item : items(0)) {
                if (topic.equals(item.get("topic")) && key.equals(item.get("key"))) {
                    return item;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no " + topic + " record for " + key + " in the feed");
    }

    private AgentOperation op(UUID id, OperationDecision decision, String actual) {
        return AgentOperation.newBuilder().setOperationId(id).setTenantId(tenant).setAgentId(agent).setTaskId(null)
                .setKind(OperationKind.LLM_CALL).setProvider("OpenAI").setModel("gpt-5").setDecision(decision)
                .setDenialCode(null).setEstimatedCost(new BigDecimal("20.000000"))
                .setActualCost(actual == null ? null : new BigDecimal(actual)).setCurrency("USD").setInputTokens(10L)
                .setOutputTokens(5L).setMandateVersion(1).setPromptSimhash(null)
                .setOccurredAt(Instant.parse("2026-11-07T10:00:00Z")).build();
    }

    private void postOperation(AgentOperation op) throws Exception {
        mvc.perform(post("/internal/v1/gateway/operations").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(SingleObject.encode(op)))
                .andExpect(status().isNoContent());
    }

    @Test
    void aBudgetReachesTheFeedInTheGatewaysEncoding() throws Exception {
        String company = budget("{\"name\":\"C\",\"level\":\"company\",\"limit\":750,\"currency\":\"USD\"}");

        Map<String, Object> item = waitFor("budgets.limits.v1", company);

        BudgetLimit limit = SingleObject.decode(Base64.getDecoder().decode((String) item.get("value")),
                BudgetLimit.class);
        assertThat(limit.getTenantId()).isEqualTo(tenant);
        assertThat(limit.getBudgetId().toString()).isEqualTo(company);
        assertThat(limit.getLimitAmount()).isEqualByComparingTo("750");
    }

    @Test
    void theInternalPathsTakeOnlyTheInternalToken() throws Exception {
        mvc.perform(get("/internal/v1/gateway/feed")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/gateway/feed").header("X-Internal-Token", TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/gateway/feed").header("X-Internal-Token", TOKEN.substring(1)))
                .andExpect(status().isUnauthorized());
        // a bearer token is not even read here
        mvc.perform(get("/internal/v1/gateway/feed").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        // a person signed in, even the owner, is not the gateway (jwt() signs in past the filters, hence 403)
        mvc.perform(get("/internal/v1/gateway/feed").with(as("owner"))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/gateway/operations").with(as("owner"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(SingleObject.encode(op(UUID.randomUUID(), OperationDecision.ALLOWED, "1"))))
                .andExpect(status().isForbidden());
        // and the internal token opens no company data
        mvc.perform(get("/api/v1/budgets").header("X-Internal-Token", TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    void anOperationPostedByTheGatewayIsChargedOnce() throws Exception {
        String company = budget("{\"name\":\"C\",\"level\":\"company\",\"limit\":1000,\"currency\":\"USD\"}");
        String department = budget("""
                {"name":"D","level":"department","refId":"%s","parentId":"%s","limit":1000,"currency":"USD"}
                """.formatted(UUID.randomUUID(), company));
        budget("""
                {"name":"A","level":"agent","refId":"%s","parentId":"%s","limit":1000,"currency":"USD"}
                """.formatted(agent, department));
        UUID id = UUID.randomUUID();

        postOperation(op(id, OperationDecision.ALLOWED, "12.500000"));
        postOperation(op(id, OperationDecision.ALLOWED, "12.500000"));

        String spent = mvc.perform(get("/api/v1/budgets/" + company).with(as("auditor"))).andReturn().getResponse()
                .getContentAsString();
        assertThat(((Number) JsonPath.read(spent, "$.spent")).doubleValue()).isEqualTo(12.5);
        mvc.perform(get("/api/v1/operations/" + id + "/receipt").with(as("auditor"))).andExpect(status().isOk());
    }

    @Test
    void aHeldOperationOpensAnApproval() throws Exception {
        UUID id = UUID.randomUUID();

        postOperation(op(id, OperationDecision.PENDING_APPROVAL, null));

        String list = mvc.perform(get("/api/v1/approvals").with(as("finance"))).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(list, "$[0].operationId")).isEqualTo(id.toString());
    }

    @Test
    void theJournalShowsEveryDecisionOncePerOperationAndOnlyToItsCompany() throws Exception {
        UUID held = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        AgentOperation pending = op(held, OperationDecision.PENDING_APPROVAL, null);
        AgentOperation allowed = op(held, OperationDecision.ALLOWED, "20.000000");
        allowed.setOccurredAt(pending.getOccurredAt().plusSeconds(60));
        AgentOperation refused = op(denied, OperationDecision.DENIED, null);
        refused.setDenialCode("budget_exceeded");
        refused.setOccurredAt(pending.getOccurredAt().plusSeconds(30));

        postOperation(pending);
        postOperation(refused);
        postOperation(allowed);
        postOperation(pending); // a late resend of the earlier decision does not undo the approval

        String list = mvc.perform(get("/api/v1/operations").with(as("auditor"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(list, "$[*].id")).containsExactly(held.toString(), denied.toString());
        assertThat((List<String>) JsonPath.read(list, "$[*].decision")).containsExactly("ALLOWED", "DENIED");
        assertThat((List<String>) JsonPath.read(list, "$[*].denialCode")).containsExactly(null, "budget_exceeded");
        assertThat((String) JsonPath.read(list, "$[0].kind")).isEqualTo("LLM_CALL");

        String paged = mvc.perform(get("/api/v1/operations").param("before", allowed.getOccurredAt().toString())
                .with(as("auditor"))).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(paged, "$[*].id")).containsExactly(denied.toString());
        String byAgent = mvc.perform(get("/api/v1/operations").param("agentId", UUID.randomUUID().toString())
                .with(as("auditor"))).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(byAgent, "$")).isEmpty();

        String other = mvc.perform(get("/api/v1/operations").with(jwt().jwt(j -> j
                        .claim("tenant_id", UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("auditor")))).authorities(realmRoles)))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(other, "$")).isEmpty();
    }

    @Test
    void aBodyThatIsNotAnOperationIsRefused() throws Exception {
        mvc.perform(post("/internal/v1/gateway/operations").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1, 2, 3}))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aKeyKeepsItsNewestValueAndMovesPastWhatTheGatewaySaw() throws Exception {
        String a = "test-" + UUID.randomUUID();
        String b = "test-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> feed.write("agents.state.v1", a, new byte[] {1}));
        long seen = items(0).stream().mapToLong(i -> ((Number) i.get("seq")).longValue()).max().orElseThrow();
        tx.executeWithoutResult(s -> feed.write("agents.state.v1", b, new byte[] {2}));
        tx.executeWithoutResult(s -> feed.write("agents.state.v1", a, new byte[] {3}));
        tx.executeWithoutResult(s -> feed.write("agents.state.v1", b, null));
        // a topic the gateway does not read is not kept
        tx.executeWithoutResult(s -> feed.write("agent.operations.v1", a, new byte[] {4}));

        List<Map<String, Object>> after = items(seen).stream()
                .filter(i -> a.equals(i.get("key")) || b.equals(i.get("key"))).toList();

        assertThat(after).extracting(i -> i.get("key")).containsExactly(a, b);
        assertThat(after.get(0).get("value")).isEqualTo(Base64.getEncoder().encodeToString(new byte[] {3}));
        assertThat(after.get(1).get("value")).as("a tombstone").isNull();
        assertThat(after).extracting(i -> i.get("topic")).containsOnly("agents.state.v1");
    }

    @Test
    void theFeedNamesTheEpochOfItsDatabase() throws Exception {
        String first = mvc.perform(get("/internal/v1/gateway/feed").param("after", "999999999")
                .header("X-Internal-Token", TOKEN)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/internal/v1/gateway/feed").header("X-Internal-Token", TOKEN))
                .andReturn().getResponse().getContentAsString();

        String epoch = JsonPath.read(first, "$.epoch");
        assertThat(UUID.fromString(epoch)).isEqualTo(feed.epoch());
        assertThat((String) JsonPath.read(second, "$.epoch")).isEqualTo(epoch);
        assertThat((List<?>) JsonPath.read(first, "$.items")).isEmpty();
    }

    @Test
    void directTransportRefusesKafkaBesideIt() {
        assertThatThrownBy(() -> new DirectFeedConfig(true)).hasMessageContaining("CORE_EVENTS_KAFKA_ENABLED=false");
    }
}
