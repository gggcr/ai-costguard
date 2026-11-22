package ai.costguard.gateway.egress;

import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.rules.PriceBook;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * The price of one call to a paid API (ADR 0010). The endpoint is found by method and path; its price is the
 * tenant's catalog REQUEST price of the vendor and product from {@code catalog.prices.v1}, else the price in the
 * configuration, in the route's currency. Either is charged in the currency of the company's budgets (ADR 0072). A
 * call with neither has no price, and the ladder refuses it with {@code price_unknown}.
 */
@Component
public class EgressPricing {

    /** What the configuration says about one call, before the tenant is known. */
    public record Endpoint(String vendor, String product, BigDecimal configuredPrice, String configuredCurrency) {
    }

    private final EgressProperties properties;
    private final PriceBook prices;
    private final AntPathMatcher paths = new AntPathMatcher();

    public EgressPricing(EgressProperties properties, PriceBook prices) {
        this.properties = properties;
        this.prices = prices;
    }

    public Optional<Endpoint> endpoint(String route, String method, String path) {
        return properties.route(route).map(r -> r.operations().stream()
                .filter(o -> o.method() == null || o.method().equalsIgnoreCase(method))
                .filter(o -> paths.match(o.path(), path))
                .findFirst()
                .map(o -> new Endpoint(r.vendor(), o.product() != null ? o.product() : r.product(),
                        o.price() != null ? o.price() : r.price(), r.currency()))
                .orElseGet(() -> new Endpoint(r.vendor(), r.product(), r.price(), r.currency())));
    }

    /**
     * The catalog's price wins; a catalog price in a currency the gateway has no rate for is no price, rather than
     * the configuration's, which the company did not set.
     */
    public Optional<BigDecimal> price(UUID tenantId, Endpoint endpoint, LocalDate day) {
        if (prices.price(tenantId, endpoint.vendor(), endpoint.product(), PriceUnit.REQUEST, day).isPresent()) {
            return prices.unitPrice(tenantId, endpoint.vendor(), endpoint.product(), PriceUnit.REQUEST, day);
        }
        return Optional.ofNullable(endpoint.configuredPrice())
                .flatMap(p -> prices.inCompanyCurrency(tenantId, p, endpoint.configuredCurrency()));
    }
}
