package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.journal.Receipts;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.OperationKind;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
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

/** The inbox wired to the real modules; Kafka itself is replaced by calling the handler. */
@SpringBootTest
@AutoConfigureMockMvc
class OperationInboxIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired OperationPosting ledger;
    @Autowired Receipts receipts;
    @Autowired ApprovalRequests approvals;

    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private String budget(String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(as("finance"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private AgentOperation op(UUID id, OperationDecision decision, String actual) {
        return AgentOperation.newBuilder().setOperationId(id).setTenantId(tenant).setAgentId(agent).setTaskId(null)
                .setKind(OperationKind.LLM_CALL).setProvider("OpenAI").setModel("gpt-5").setDecision(decision)
                .setDenialCode(null).setEstimatedCost(new BigDecimal("20.000000"))
                .setActualCost(actual == null ? null : new BigDecimal(actual)).setCurrency("RUB").setInputTokens(10L)
                .setOutputTokens(5L).setMandateVersion(1).setPromptSimhash(null)
                .setOccurredAt(Instant.parse("2026-11-07T10:00:00Z")).build();
    }

    @Test
    void anAllowedOperationIsChargedAndGetsAReceiptOnce() throws Exception {
        String company = budget("{\"name\":\"C\",\"level\":\"company\",\"limit\":1000,\"currency\":\"RUB\"}");
        String department = budget("""
                {"name":"D","level":"department","refId":"%s","parentId":"%s","limit":1000,"currency":"RUB"}
                """.formatted(UUID.randomUUID(), company));
        budget("""
                {"name":"A","level":"agent","refId":"%s","parentId":"%s","limit":1000,"currency":"RUB"}
                """.formatted(agent, department));
        OperationInbox inbox = new OperationInbox(ledger, receipts, approvals);
        UUID id = UUID.randomUUID();

        inbox.handle(op(id, OperationDecision.ALLOWED, "12.500000"));
        inbox.handle(op(id, OperationDecision.ALLOWED, "12.500000"));

        mvc.perform(get("/api/v1/budgets/" + company).with(as("auditor"))).andExpect(jsonPath("$.spent").value(12.5));
        mvc.perform(get("/api/v1/operations/" + id + "/receipt").with(as("auditor"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/journal/verification").with(as("auditor"))).andExpect(jsonPath("$.receipts").value(1));
    }

    @Test
    void aHeldOperationOpensOneApproval() throws Exception {
        OperationInbox inbox = new OperationInbox(ledger, receipts, approvals);
        UUID id = UUID.randomUUID();

        inbox.handle(op(id, OperationDecision.PENDING_APPROVAL, null));
        inbox.handle(op(id, OperationDecision.PENDING_APPROVAL, null));

        String list = mvc.perform(get("/api/v1/approvals").with(as("finance"))).andReturn().getResponse()
                .getContentAsString();
        assertThat((List<?>) JsonPath.read(list, "$")).hasSize(1);
        assertThat((String) JsonPath.read(list, "$[0].operationId")).isEqualTo(id.toString());
    }

    @Test
    void aDeniedOperationTouchesNothing() throws Exception {
        new OperationInbox(ledger, receipts, approvals).handle(op(UUID.randomUUID(), OperationDecision.DENIED, null));

        mvc.perform(get("/api/v1/approvals").with(as("finance"))).andExpect(jsonPath("$.length()").value(0));
    }
}
