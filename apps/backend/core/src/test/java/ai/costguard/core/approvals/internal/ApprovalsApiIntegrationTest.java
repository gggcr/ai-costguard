package ai.costguard.core.approvals.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "costguard.approvals.timer-delay=PT1H")
@AutoConfigureMockMvc
@Import(ApprovalsApiIntegrationTest.Support.class)
class ApprovalsApiIntegrationTest extends PostgresTestSupport {

    static class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-07T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Support {
        final List<ApprovalDecided> decided = new CopyOnWriteArrayList<>();
        final List<ApprovalEscalated> escalated = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }

        @EventListener
        void on(ApprovalDecided event) {
            decided.add(event);
        }

        @EventListener
        void on(ApprovalEscalated event) {
            escalated.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired ApprovalRequests requests;
    @Autowired ApprovalTimer timer;
    @Autowired MovableClock clock;
    @Autowired Support support;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String email, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor finance() {
        return as(tenant, "fin@x.ru", "finance");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID request(UUID agent) {
        return requests.open(tenant, UUID.randomUUID(), agent, "Big call", new BigDecimal("1500"), "Above threshold");
    }

    private UUID request() {
        return request(UUID.randomUUID());
    }

    private List<ApprovalDecided> decidedFor(UUID id) {
        return support.decided.stream().filter(e -> e.approvalId().equals(id)).toList();
    }

    @Test
    void openingTwiceForOneOperationGivesOneRequest() throws Exception {
        UUID operation = UUID.randomUUID();
        UUID agent = UUID.randomUUID();

        UUID first = requests.open(tenant, operation, agent, "d", BigDecimal.TEN, "r");
        UUID second = requests.open(tenant, operation, agent, "d", BigDecimal.TEN, "r");

        assertThat(second).isEqualTo(first);
        mvc.perform(get("/api/v1/approvals").with(finance())).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void anApprovalIsDecidedOnce() throws Exception {
        UUID id = request();

        mvc.perform(json(put("/api/v1/approvals/" + id + "/decision"), "{\"verdict\":\"approve\",\"comment\":\"ok\"}")
                        .with(finance()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.decidedBy").value("fin@x.ru"));
        mvc.perform(json(put("/api/v1/approvals/" + id + "/decision"), "{\"verdict\":\"reject\"}").with(finance()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_decided"));

        assertThat(decidedFor(id)).hasSize(1);
        assertThat(decidedFor(id).getFirst().status()).isEqualTo("approved");
    }

    @Test
    void theTimerEscalatesAtHalfTimeThenExpires() throws Exception {
        UUID id = request();

        clock.advance(Duration.ofHours(2));
        timer.tick();
        timer.tick();

        assertThat(support.escalated.stream().filter(e -> e.approvalId().equals(id))).hasSize(1);
        mvc.perform(get("/api/v1/approvals/" + id).with(finance()))
                .andExpect(jsonPath("$.escalated").value(true)).andExpect(jsonPath("$.status").value("pending"));

        clock.advance(Duration.ofHours(2));
        timer.tick();
        timer.tick();

        mvc.perform(get("/api/v1/approvals/" + id).with(finance())).andExpect(jsonPath("$.status").value("expired"));
        assertThat(decidedFor(id)).hasSize(1);
        assertThat(decidedFor(id).getFirst().status()).isEqualTo("expired");
    }

    @Test
    void theTimerWorksAcrossTenantsUnderRowLevelSecurity() throws Exception {
        UUID other = UUID.randomUUID();
        UUID foreign = requests.open(other, UUID.randomUUID(), UUID.randomUUID(), "d", BigDecimal.ONE, "r");
        UUID own = request();

        clock.advance(Duration.ofHours(5));
        timer.tick();

        mvc.perform(get("/api/v1/approvals/" + own).with(finance())).andExpect(jsonPath("$.status").value("expired"));
        mvc.perform(get("/api/v1/approvals/" + foreign).with(as(other, "a@x.ru", "finance")))
                .andExpect(jsonPath("$.status").value("expired"));
    }

    @Test
    void aLateDecisionIsRefusedEvenBeforeTheTimerRuns() throws Exception {
        UUID id = request();

        clock.advance(Duration.ofHours(5));

        mvc.perform(json(put("/api/v1/approvals/" + id + "/decision"), "{\"verdict\":\"approve\"}").with(finance()))
                .andExpect(status().isConflict());
    }

    @Test
    void aDecidedRequestIsLeftAloneByTheTimer() throws Exception {
        UUID id = request();
        mvc.perform(json(put("/api/v1/approvals/" + id + "/decision"), "{\"verdict\":\"reject\"}").with(finance()))
                .andExpect(status().isOk());

        clock.advance(Duration.ofHours(5));
        timer.tick();

        mvc.perform(get("/api/v1/approvals/" + id).with(finance())).andExpect(jsonPath("$.status").value("rejected"));
        assertThat(support.escalated.stream().filter(e -> e.approvalId().equals(id))).isEmpty();
    }

    @Test
    void anAgentOwnerDecidesOnlyOnTheirOwnAgents() throws Exception {
        RequestPostProcessor admin = as(tenant, "adm@x.ru", "owner");
        String email = UUID.randomUUID() + "@x.ru";
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String employee = JsonPath.read(mvc.perform(json(post("/api/v1/employees"), """
                {"name":"O","email":"%s","departmentId":"%s","position":"P"}
                """.formatted(email, department)).with(admin)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/users"), """
                {"name":"O","email":"%s","role":"agent_owner","employeeId":"%s"}
                """.formatted(email, employee)).with(admin)).andExpect(status().isCreated());
        String mandate = JsonPath.read(mvc.perform(json(post("/api/v1/mandates"), """
                {"name":"%s","models":["m"],"vendors":["v"],"maxPerOperation":1,"approvalThreshold":1,
                 "hours":"круглосуточно"}
                """.formatted(UUID.randomUUID())).with(admin)).andReturn().getResponse().getContentAsString(), "$.id");
        String agent = JsonPath.read(mvc.perform(json(post("/api/v1/agents"), """
                {"name":"B","description":"","framework":"f","departmentId":"%s","ownerId":"%s","mandateId":"%s"}
                """.formatted(department, employee, mandate)).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.id");

        RequestPostProcessor agentOwner = as(tenant, email, "agent_owner");
        mvc.perform(json(put("/api/v1/approvals/" + request() + "/decision"), "{\"verdict\":\"approve\"}")
                        .with(agentOwner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_agent_owner"));
        mvc.perform(json(put("/api/v1/approvals/" + request(UUID.fromString(agent)) + "/decision"),
                        "{\"verdict\":\"approve\"}").with(agentOwner))
                .andExpect(status().isOk());
    }

    @Test
    void itAdminCannotDecide() throws Exception {
        mvc.perform(json(put("/api/v1/approvals/" + request() + "/decision"), "{\"verdict\":\"approve\"}")
                        .with(as(tenant, "a@x.ru", "it_admin")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        UUID id = request();
        UUID other = UUID.randomUUID();

        mvc.perform(get("/api/v1/approvals/" + id).with(as(other, "a@x.ru", "owner"))).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/approvals/" + id + "/decision"), "{\"verdict\":\"approve\"}")
                .with(as(other, "a@x.ru", "owner"))).andExpect(status().isNotFound());
    }
}
