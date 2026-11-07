package ai.costguard.core.notifications.internal;

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
import java.time.Duration;
import java.util.function.Supplier;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationsApiIntegrationTest extends PostgresTestSupport {

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

    @Autowired FindingsIntake findings;
    @Autowired NotificationStream stream;

    /** Listeners run after commit on another thread. */
    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(50);
        }
    }

    private int unread(UUID tenantId) {
        try {
            String body = mvc.perform(get("/api/v1/notifications").with(as(tenantId, "auditor")))
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.unread");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aNewFindingReachesTheFeedOfItsTenantOnly() throws Exception {
        findings.detected(tenant, "fp-" + UUID.randomUUID(), "duplicates", "Two Zoom plans", "d",
                new BigDecimal("1200"), null, null);

        eventually(() -> unread(tenant) == 1);
        mvc.perform(get("/api/v1/notifications").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.items[0].text").value("Новая находка: Two Zoom plans, 1200 ₽/мес"))
                .andExpect(jsonPath("$.items[0].level").value("info"));
        assertThat(unread(UUID.randomUUID())).isZero();
    }

    @Test
    void readingAndClearing() throws Exception {
        findings.detected(tenant, "fp-" + UUID.randomUUID(), "r", "A", "d", BigDecimal.ONE, null, null);
        findings.detected(tenant, "fp-" + UUID.randomUUID(), "r", "B", "d", BigDecimal.ONE, null, null);
        eventually(() -> unread(tenant) == 2);

        mvc.perform(put("/api/v1/notifications/read").with(as(tenant, "auditor"))).andExpect(status().isNoContent());
        assertThat(unread(tenant)).isZero();
        mvc.perform(delete("/api/v1/notifications").with(as(tenant, "auditor"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").with(as(tenant, "auditor"))).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void theStreamOpensForTheTenant() throws Exception {
        mvc.perform(get("/api/v1/notifications/stream").with(as(tenant, "auditor")))
                .andExpect(status().isOk());

        assertThat(stream.connections(tenant)).isEqualTo(1);
        assertThat(stream.connections(UUID.randomUUID())).isZero();
    }
}
