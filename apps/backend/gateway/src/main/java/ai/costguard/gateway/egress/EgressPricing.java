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
 * configuration. A call with neither has no price, and the ladder refuses it with {@code price_unknown}.
 */
@Component
public class EgressPricing {

    /** What the configuration says about one call, before the tenant is known. */
    public record Endpoint(String vendor, String product, BigDecimal configuredPrice) {
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
                        o.price() != null ? o.price() : r.price()))
                .orElseGet(() -> new Endpoint(r.vendor(), r.product(), r.price())));
    }

    public Optional<BigDecimal> price(UUID tenantId, Endpoint endpoint, LocalDate day) {
        return prices.unitPrice(tenantId, endpoint.vendor(), endpoint.product(), PriceUnit.REQUEST, day)
                .or(() -> Optional.ofNullable(endpoint.configuredPrice()));
    }
}
