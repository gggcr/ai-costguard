package ai.costguard.core.findings.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
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

/**
 * The scheduled round, not the Audit interface: it calls run() on itself, past the transactional proxy, and once
 * found nothing at all because the tenant binding had no transaction to live in.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditScheduleIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired AuditEngine engine;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor owner() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of("owner")))).authorities(realmRoles);
    }

    String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(owner())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theScheduledRoundFindsWhatTheRulesSee() throws Exception {
        String vendor = create("/api/v1/vendors", "{\"name\":\"Ромашка-%s\",\"inn\":\"\",\"category\":\"\"}"
                .formatted(UUID.randomUUID()));
        create("/api/v1/subscriptions", """
                {"vendorId":"%s","product":"CRM","contractId":null,"departmentId":"%s","seats":2,
                 "pricePerSeat":100,"billingPeriod":"month","renewalDate":"2027-01-01"}""".formatted(vendor,
                UUID.randomUUID()));

        engine.everyTenant();

        mvc.perform(get("/api/v1/findings").with(owner()))
                .andExpect(jsonPath("$[?(@.rule == 'subscription.idle-seats')].expectedSavings").value(200.0));
    }
}
