package ai.costguard.core.findings.internal;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import ai.costguard.core.findings.FindingsIntake;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = "costguard.findings.timer-delay=PT1H")
@AutoConfigureMockMvc
@Import(FindingsApiIntegrationTest.Support.class)
class FindingsApiIntegrationTest extends PostgresTestSupport {

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
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "u@x.ru")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Autowired FindingsIntake intake;
    @Autowired FindingTimer timer;
    @Autowired MovableClock clock;

    private UUID detect(String fingerprint) {
        return intake.detected(tenant, fingerprint, "unused_licenses", "Jira: 4 seats unused", "d",
                new BigDecimal("3160"), null, null);
    }

    private void act(UUID id, String body, int expected) throws Exception {
        mvc.perform(json(post("/api/v1/findings/" + id + "/actions"), body).with(as(tenant, "finance")))
                .andExpect(status().is(expected));
    }

    private void expectStatus(UUID id, String status) throws Exception {
        mvc.perform(get("/api/v1/findings/" + id).with(as(tenant, "auditor"))).andExpect(jsonPath("$.status").value(status));
    }

    @Test
    void theFullPathToConfirmedSavings() throws Exception {
        UUID id = detect("fp-" + UUID.randomUUID());

        act(id, "{\"action\":\"accept\"}", 409);
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()), 200);
        act(id, "{\"action\":\"accept\"}", 200);
        act(id, "{\"action\":\"createTask\",\"reason\":\"cut seats\"}", 200);
        act(id, "{\"action\":\"missInvoice\"}", 200);
        expectStatus(id, "in_progress");
        act(id, "{\"action\":\"confirm\"}", 200);

        expectStatus(id, "confirmed");
        mvc.perform(get("/api/v1/findings/totals").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.confirmedSavings").value(3160.0)).andExpect(jsonPath("$.open").value(0));
        mvc.perform(get("/api/v1/findings/" + id).with(as(tenant, "auditor"))).andExpect(jsonPath("$.history", hasSize(6)));
    }

    @Test
    void twoMissedInvoicesSendItBackToReview() throws Exception {
        UUID id = detect("fp-" + UUID.randomUUID());
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()), 200);
        act(id, "{\"action\":\"accept\"}", 200);
        act(id, "{\"action\":\"createTask\"}", 200);

        act(id, "{\"action\":\"missInvoice\"}", 200);
        act(id, "{\"action\":\"missInvoice\"}", 200);

        expectStatus(id, "review");
    }

    @Test
    void aFindingClosesAndReopensWithItsCondition() throws Exception {
        String fingerprint = "fp-" + UUID.randomUUID();
        UUID id = detect(fingerprint);

        intake.resolved(tenant, fingerprint);
        expectStatus(id, "auto_closed");
        assertThat(detect(fingerprint)).isEqualTo(id);
        expectStatus(id, "new");
    }

    @Test
    void anAcceptedFindingDoesNotCloseByItself() throws Exception {
        String fingerprint = "fp-" + UUID.randomUUID();
        UUID id = detect(fingerprint);
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()), 200);
        act(id, "{\"action\":\"accept\"}", 200);

        intake.resolved(tenant, fingerprint);

        expectStatus(id, "accepted");
    }

    @Test
    void aPostponedFindingWakesUpOnItsDate() throws Exception {
        UUID id = detect("fp-" + UUID.randomUUID());
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()), 200);
        act(id, "{\"action\":\"postpone\",\"date\":\"2026-11-07\"}", 400);
        act(id, "{\"action\":\"postpone\",\"date\":\"2026-11-10\"}", 200);

        timer.tick();
        expectStatus(id, "postponed");
        clock.advance(Duration.ofDays(3));
        timer.tick();

        expectStatus(id, "new");
    }

    @Test
    void rejectingNeedsAReason() throws Exception {
        UUID id = detect("fp-" + UUID.randomUUID());
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()), 200);

        act(id, "{\"action\":\"reject\"}", 400);
        act(id, "{\"action\":\"reject\",\"reason\":\"needed\"}", 200);
        expectStatus(id, "rejected");
    }

    @Test
    void tenantsAreIsolatedAndAuditorsOnlyRead() throws Exception {
        UUID id = detect("fp-" + UUID.randomUUID());

        mvc.perform(get("/api/v1/findings/" + id).with(as(UUID.randomUUID(), "owner"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/findings/" + id + "/actions"), "{\"action\":\"assign\"}")
                .with(as(tenant, "auditor"))).andExpect(status().isForbidden());
    }
}
