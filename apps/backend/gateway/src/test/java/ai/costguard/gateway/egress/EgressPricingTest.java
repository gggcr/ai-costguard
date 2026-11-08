package ai.costguard.gateway.egress;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.CatalogPrice;
import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.rules.PriceBook;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class EgressPricingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);
    private final UUID tenant = UUID.randomUUID();
    private final PriceBook prices = new PriceBook();

    private final EgressProperties properties = new EgressProperties(Map.of(
            "spark", new EgressProperties.Route(URI.create("http://spark"), "SPARK", "request", new BigDecimal("15"),
                    "Authorization", "Bearer api-key", List.of(
                    new EgressProperties.Priced("/v1/companies/{inn}/report", "GET", "report", new BigDecimal("450")),
                    new EgressProperties.Priced("/v1/search/**", null, "search", null))),
            "cloud", new EgressProperties.Route(URI.create("http://cloud"), "Cloud", null, null, null, null, null)));
    private final EgressPricing pricing = new EgressPricing(properties, prices);

    private void catalog(String vendor, String product, String price) {
        prices.apply(CatalogPrice.newBuilder().setTenantId(tenant).setSkuId(vendor + ":" + product).setProvider(vendor)
                .setModel(product).setUnit(PriceUnit.REQUEST)
                .setUnitPrice(new BigDecimal(price).setScale(6, RoundingMode.HALF_EVEN)).setCurrency("RUB")
                .setValidFrom(LocalDate.of(2026, 1, 1)).build());
    }

    private BigDecimal price(String route, String method, String path) {
        return pricing.endpoint(route, method, path).flatMap(e -> pricing.price(tenant, e, DAY))
                .map(BigDecimal::stripTrailingZeros).orElse(null);
    }

    @Test
    void anEndpointHasItsOwnProductAndPrice() {
        assertThat(pricing.endpoint("spark", "GET", "/v1/companies/7707083893/report"))
                .contains(new EgressPricing.Endpoint("SPARK", "report", new BigDecimal("450")));
        assertThat(price("spark", "GET", "/v1/companies/7707083893/report")).isEqualByComparingTo("450");
    }

    @Test
    void theMethodMustMatchWhenItIsGiven() {
        assertThat(pricing.endpoint("spark", "POST", "/v1/companies/7707083893/report"))
                .contains(new EgressPricing.Endpoint("SPARK", "request", new BigDecimal("15")));
    }

    @Test
    void anEndpointWithoutAPriceTakesTheRoutePrice() {
        assertThat(pricing.endpoint("spark", "POST", "/v1/search/companies"))
                .contains(new EgressPricing.Endpoint("SPARK", "search", new BigDecimal("15")));
    }

    @Test
    void theTenantCatalogWinsOverTheConfiguration() {
        catalog("SPARK", "report", "380");
        assertThat(price("spark", "GET", "/v1/companies/1/report")).isEqualByComparingTo("380");
        assertThat(price("spark", "GET", "/v1/other")).isEqualByComparingTo("15");
    }

    @Test
    void withoutCatalogOrConfigurationThereIsNoPrice() {
        assertThat(price("cloud", "GET", "/v1/vm")).isNull();
        catalog("Cloud", null, "2.5");
        assertThat(price("cloud", "GET", "/v1/vm")).isEqualByComparingTo("2.5");
    }

    @Test
    void anUnknownRouteHasNoEndpoint() {
        assertThat(pricing.endpoint("nope", "GET", "/")).isEmpty();
    }

    @Test
    void thePathIsWhatFollowsTheRoute() {
        assertThat(EgressRoutes.apiPath("spark", "/v1/egress/spark/v1/companies")).isEqualTo("/v1/companies");
        assertThat(EgressRoutes.apiPath("spark", "/v1/egress/spark")).isEqualTo("/");
    }

    @Test
    void theAgentKeyIsReplacedByTheApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer cg_live_agent");
        headers.set("x-api-key", "cg_live_agent");
        headers.set("X-CostGuard-Task-Goal", "goal");
        headers.set("Accept", "application/json");

        EgressRoutes.credentials(properties.route("spark").orElseThrow(), headers);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer api-key");
        assertThat(headers.containsHeader("x-api-key")).isFalse();
        assertThat(headers.containsHeader("X-CostGuard-Task-Goal")).isFalse();
        assertThat(headers.getFirst("Accept")).isEqualTo("application/json");

        HttpHeaders keyless = new HttpHeaders();
        keyless.set(HttpHeaders.AUTHORIZATION, "Bearer cg_live_agent");
        EgressRoutes.credentials(properties.route("cloud").orElseThrow(), keyless);
        assertThat(keyless.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }
}
