package ai.costguard.core.approvals.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.approvals.LargeOperationApproved;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Finding H2 of the audit of 2026-11-12: who decided an approval was the token's e-mail (or, for the agent owner's
 * own decision, the token's name), written to decided_by and hashed into the approver hash the NEAR notary keeps
 * for ever. Anyone able to put another address or name on their Keycloak account could leave someone else in that
 * trail. The decider is now the CostGuard account of the token's subject: decided_by is its stored address, and the
 * approver hash v2 is made from the account id.
 *
 * <p>The expected v2 hash is computed here, independently of the code. All data is fictitious.
 */
@SpringBootTest(properties = "costguard.notary.confirm-above=1000")
@AutoConfigureMockMvc
@Import(ApproverIdentityIntegrationTest.Events.class)
class ApproverIdentityIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Events {
        final List<LargeOperationApproved> large = new CopyOnWriteArrayList<>();

        @EventListener
        void on(LargeOperationApproved event) {
            large.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired ApprovalRequests requests;
    @Autowired Events events;

    private final UUID tenant = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String financeMail = "fin-" + run + "@approvals.example";
    private final String victimMail = "victim-" + run + "@approvals.example";
    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor token(UUID tenantId, String sub, String email, String name, String... roles) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenantId.toString()).claim("email", email)
                        .claim("email_verified", false).claim("name", name)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    /** The finance person, signed in as themselves but with the victim's address and name on the token. */
    private RequestPostProcessor financeAsVictim() {
        return token(tenant, subject(tenant, financeMail), victimMail, "Жертва Подмены", "finance");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> request, RequestPostProcessor who)
            throws Exception {
        return mvc.perform(request.with(who)).andReturn().getResponse();
    }

    private static String body(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private UUID large(UUID agent) {
        return requests.open(tenant, UUID.randomUUID(), agent, "Крупная операция", new BigDecimal("1500"), "Порог");
    }

    /** The approval as stored, every column by name (a column the schema lacks reads null). */
    private Map<String, Object> row(UUID approval) {
        String json = su.sql("select to_jsonb(a)::text from approval_request a where id = ?").param(approval)
                .query(String.class).single();
        Map<String, Object> all = JsonPath.read(json, "$");
        Map<String, Object> out = new java.util.HashMap<>();
        for (String c : List.of("status", "decided_by", "decided_by_account", "approver_hash", "approver_hash_version")) {
            Object v = all.get(c);
            out.put(c, v == null ? null : v.toString());
        }
        return out;
    }

    private LargeOperationApproved eventOf(UUID approval) {
        return events.large.stream().filter(e -> e.approvalId().equals(approval)).findFirst().orElse(null);
    }

    private static String sha256(String... lines) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    private String v2(UUID approval, UUID account) throws Exception {
        return sha256("costguard:approver:v2", tenant.toString(), approval.toString(), account.toString());
    }

    private String v1(UUID approval, String decidedBy) throws Exception {
        return sha256("costguard:approver:v1", tenant.toString(), approval.toString(), decidedBy);
    }

    @Test
    void anotherAddressOnTheTokenDoesNotPutAnotherPersonInTheNotaryTrail() throws Exception {
        UUID finance = login(tenant, financeMail, "finance", null);
        login(tenant, victimMail, "finance", null);
        UUID approval = large(UUID.randomUUID());

        MockHttpServletResponse r = call(json(put("/api/v1/approvals/{id}/decision", approval),
                "{\"verdict\":\"approve\"}"), financeAsVictim());
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);

        Map<String, Object> decided = row(approval);
        assertThat(decided).containsEntry("status", "approved")
                .as("decided_by is the decider's stored address, not the token's").containsEntry("decided_by", financeMail)
                .containsEntry("decided_by_account", finance.toString()).containsEntry("approver_hash_version", "2");
        LargeOperationApproved sent = eventOf(approval);
        assertThat(sent).as("the operation goes to the notary").isNotNull();
        assertThat(sent.approverHash()).as("v2 from the decider's account").isEqualTo(v2(approval, finance))
                .isEqualTo(decided.get("approver_hash"))
                .isNotEqualTo(v1(approval, victimMail)).isNotEqualTo(v1(approval, financeMail));
        assertThat(body(r)).doesNotContain(victimMail);
    }

    @Test
    void theSamePersonWithAnyAddressOnTheTokenIsTheSameApprover() throws Exception {
        UUID finance = login(tenant, financeMail, "finance", null);
        UUID first = large(UUID.randomUUID());
        UUID second = large(UUID.randomUUID());
        call(json(put("/api/v1/approvals/{id}/decision", first), "{\"verdict\":\"approve\"}"),
                token(tenant, subject(tenant, financeMail), financeMail, "Финансист", "finance"));
        call(json(put("/api/v1/approvals/{id}/decision", second), "{\"verdict\":\"approve\"}"),
                token(tenant, subject(tenant, financeMail), "changed-" + run + "@elsewhere.example", "Другое", "finance"));

        assertThat(row(first)).containsEntry("decided_by_account", finance.toString())
                .containsEntry("decided_by", financeMail);
        assertThat(row(second)).containsEntry("decided_by_account", finance.toString())
                .containsEntry("decided_by", financeMail);
        assertThat(eventOf(first).approverHash()).isEqualTo(v2(first, finance));
        assertThat(eventOf(second).approverHash()).isEqualTo(v2(second, finance));
    }

    @Test
    void aSignInWithNoAccountDecidesNothingWhateverAddressItCarries() throws Exception {
        login(tenant, financeMail, "finance", null);
        UUID approval = large(UUID.randomUUID());
        MockHttpServletResponse r = call(json(put("/api/v1/approvals/{id}/decision", approval),
                "{\"verdict\":\"approve\"}"), token(tenant, "kc-nobody-" + run, financeMail, "Финансист", "finance"));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(403);
        assertThat(body(r)).contains("decider_account_required");
        assertThat(row(approval)).containsEntry("status", "pending").containsEntry("decided_by", null);
        assertThat(eventOf(approval)).isNull();
    }

    @Test
    void anotherCompanyCannotDecideOrSeeTheApproval() throws Exception {
        login(tenant, financeMail, "finance", null);
        UUID approval = large(UUID.randomUUID());
        MockHttpServletResponse r = call(json(put("/api/v1/approvals/{id}/decision", approval),
                "{\"verdict\":\"approve\"}"), token(UUID.randomUUID(), subject(tenant, financeMail), financeMail,
                "Финансист", "finance"));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(404);
        assertThat(row(approval)).containsEntry("status", "pending");
        assertThat(eventOf(approval)).isNull();
    }

    @Test
    void aDecisionIsMadeOnceAndItsHashStays() throws Exception {
        UUID finance = login(tenant, financeMail, "finance", null);
        UUID approval = large(UUID.randomUUID());
        call(json(put("/api/v1/approvals/{id}/decision", approval), "{\"verdict\":\"approve\"}"), financeAsVictim());
        String hash = (String) row(approval).get("approver_hash");
        MockHttpServletResponse again = call(json(put("/api/v1/approvals/{id}/decision", approval),
                "{\"verdict\":\"reject\"}"), token(tenant, subject(tenant, financeMail), financeMail, "Ф", "finance"));
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(row(approval)).containsEntry("status", "approved").containsEntry("approver_hash", hash);
        assertThat(hash).isEqualTo(v2(approval, finance));
    }

    // the agent owner's own decision in the cabinet (ADR 0067): it went in with the token's name

    @Test
    void theAgentOwnersDecisionInTheCabinetIsTheirAccountNotTheTokensName() throws Exception {
        RequestPostProcessor owner = token(tenant, "kc-owner-" + run, "owner-" + run + "@approvals.example", "В",
                "owner");
        String ownerMail = "agent-owner-" + run + "@approvals.example";
        String department = JsonPath.read(body(call(json(post("/api/v1/departments"),
                "{\"name\":\"%s\"}".formatted(UUID.randomUUID())), owner)), "$.id");
        String employee = JsonPath.read(body(call(json(post("/api/v1/employees"), """
                {"name":"Владелец Агента","email":"%s","departmentId":"%s","position":"P"}
                """.formatted(ownerMail, department)), owner)), "$.id");
        UUID account = login(tenant, ownerMail, "agent_owner", UUID.fromString(employee));
        String mandate = JsonPath.read(body(call(json(post("/api/v1/mandates"), """
                {"name":"%s","models":["m"],"vendors":["v"],"maxPerOperation":1,"approvalThreshold":1,
                 "hours":"круглосуточно"}""".formatted(UUID.randomUUID())), owner)), "$.id");
        String agent = JsonPath.read(body(call(json(post("/api/v1/agents"), """
                {"name":"B","description":"","framework":"f","departmentId":"%s","ownerId":"%s","mandateId":"%s"}
                """.formatted(department, employee, mandate)), owner)), "$.id");
        UUID approval = requests.open(tenant, UUID.randomUUID(), UUID.fromString(agent), "Своя операция",
                new BigDecimal("10"), "Светофор");

        RequestPostProcessor spoofed = token(tenant, subject(tenant, ownerMail), victimMail, "Жертва Подмены",
                "agent_owner");
        MockHttpServletResponse r = call(json(put("/api/v1/me/agents/operations/{id}/decision", approval),
                "{\"approve\":true}"), spoofed);
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        Map<String, Object> decided = row(approval);
        assertThat(decided).containsEntry("status", "approved").containsEntry("decided_by", ownerMail)
                .containsEntry("decided_by_account", account.toString()).containsEntry("approver_hash_version", "2");
        assertThat(eventOf(approval).approverHash()).isEqualTo(v2(approval, account))
                .isNotEqualTo(v1(approval, "Жертва Подмены"));
    }
}
