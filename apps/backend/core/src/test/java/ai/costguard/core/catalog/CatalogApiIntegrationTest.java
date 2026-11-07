package ai.costguard.core.catalog;

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
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CatalogApiIntegrationTest.Prices.class)
class CatalogApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Prices {
        final List<CatalogPriceChanged> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(CatalogPriceChanged event) {
            seen.add(event);
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

    @Autowired Prices prices;
    @Autowired ExchangeRates rates;
    @Autowired JdbcClient jdbc;

    private static String price(String sku, String amount, String from) {
        return """
                {"skuId":"%s","vendor":"OpenAI","model":"gpt-5","unit":"INPUT_TOKEN","unitPrice":%s,
                 "currency":"USD","validFrom":"%s"}
                """.formatted(sku, amount, from);
    }

    @Test
    void thePriceInForceDependsOnTheDate() throws Exception {
        String sku = "sku-" + UUID.randomUUID();
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "0.000002", "2026-01-01"))
                .with(as(tenant, "finance"))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "0.000003", "2026-06-01"))
                .with(as(tenant, "finance"))).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/catalog/prices?date=2026-03-01").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$[0].unitPrice").value(0.000002));
        mvc.perform(get("/api/v1/catalog/prices?date=2026-07-01").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$[0].unitPrice").value(0.000003));
        mvc.perform(get("/api/v1/catalog/prices/" + sku + "/history").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$", hasSize(2)));
        assertThat(prices.seen.stream().filter(p -> p.skuId().equals(sku))).hasSize(2);
    }

    @Test
    void oneSkuHasOnePricePerDay() throws Exception {
        String sku = "sku-" + UUID.randomUUID();
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "1", "2026-01-01")).with(as(tenant, "owner")));
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "2", "2026-01-01")).with(as(tenant, "owner")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("price_exists"));
    }

    @Test
    void pricesAreIsolatedAndOnlyEditorsChangeThem() throws Exception {
        String sku = "sku-" + UUID.randomUUID();
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "1", "2026-01-01")).with(as(tenant, "owner")));

        mvc.perform(get("/api/v1/catalog/prices/" + sku + "/history").with(as(UUID.randomUUID(), "owner")))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(json(post("/api/v1/catalog/prices"), price(sku, "1", "2026-02-01")).with(as(tenant, "auditor")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
    }

    @Test
    void conversionUsesTheLatestRateOnOrBeforeTheDate() throws Exception {
        jdbc.sql("insert into fx_rate values ('2026-11-06', 'EUR', 90.5), ('2026-11-08', 'EUR', 95)").update();

        assertThat(rates.toRub(new BigDecimal("10"), "EUR", LocalDate.of(2026, 11, 7))).contains(new BigDecimal("905.00"));
        assertThat(rates.toRub(new BigDecimal("10"), "RUB", LocalDate.of(2026, 11, 7))).contains(BigDecimal.TEN);
        assertThat(rates.toRub(BigDecimal.ONE, "EUR", LocalDate.of(2026, 11, 1))).isEmpty();
        mvc.perform(get("/api/v1/catalog/fx-conversion?amount=1&currency=XYZ").with(as(tenant, "auditor")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("rate_not_found"));
    }
}
