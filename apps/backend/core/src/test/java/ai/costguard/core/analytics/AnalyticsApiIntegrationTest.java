package ai.costguard.core.analytics;

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
import ai.costguard.core.ledger.OperationPosting;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsApiIntegrationTest extends PostgresTestSupport {

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

    @Autowired OperationPosting posting;
    @Autowired FindingsIntake findings;

    @Test
    void summaryCombinesSpendAndSavings() throws Exception {
        String company = JsonPath.read(mvc.perform(json(post("/api/v1/budgets"),
                "{\"name\":\"C\",\"level\":\"company\",\"limit\":100000,\"currency\":\"RUB\"}")
                .with(as(tenant, "finance"))).andReturn().getResponse().getContentAsString(), "$.id");
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/budgets"), """
                {"name":"D","level":"department","refId":"%s","parentId":"%s","limit":1000,"currency":"RUB"}
                """.formatted(UUID.randomUUID(), company)).with(as(tenant, "finance"))).andReturn().getResponse()
                .getContentAsString(), "$.id");
        UUID agent = UUID.randomUUID();
        mvc.perform(json(post("/api/v1/budgets"), """
                {"name":"A","level":"agent","refId":"%s","parentId":"%s","limit":500,"currency":"RUB"}
                """.formatted(agent, department)).with(as(tenant, "finance"))).andExpect(status().isCreated());
        Instant thisMonth = Instant.now();
        posting.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("40"), thisMonth);
        posting.charge(tenant, UUID.randomUUID(), agent, new BigDecimal("2.5"), thisMonth);
        findings.detected(tenant, "fp-" + UUID.randomUUID(), "r", "t", "d", new BigDecimal("700"), null, null);

        mvc.perform(get("/api/v1/analytics/summary?months=3").with(as(tenant, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentSpend[-1].amount").value(42.5))
                .andExpect(jsonPath("$.findings.open").value(1))
                .andExpect(jsonPath("$.findings.expectedSavings").value(700.0));
    }
}
