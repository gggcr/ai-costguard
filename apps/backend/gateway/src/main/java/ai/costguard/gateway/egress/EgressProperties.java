package ai.costguard.gateway.egress;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paid external APIs behind {@code /v1/egress/<name>/...}, under {@code costguard.gateway.egress.<name>}. The key
 * of the API ({@code header}: {@code value}) comes from an environment variable or Vault; agents never see it.
 *
 * <pre>
 * costguard.gateway.egress:
 *   spark:
 *     url: https://api.spark-interfax.ru
 *     vendor: СПАРК-Интерфакс
 *     header: Authorization
 *     value: Bearer ${SPARK_API_KEY:}
 *     product: request          # catalog model of the REQUEST price; optional
 *     price: 15                 # used when the tenant's catalog has no price; optional
 *     operations:               # a price per endpoint; the first match wins
 *       - { path: /v1/companies/{inn}/report, method: GET, product: report, price: 450 }
 * </pre>
 */
@ConfigurationProperties("costguard.gateway")
public record EgressProperties(Map<String, Route> egress) {

    /** One paid API. Without {@code operations}, or when none matches, the route's own product and price apply. */
    public record Route(URI url, String vendor, String product, BigDecimal price, String header, String value,
            List<Priced> operations) {

        public List<Priced> operations() {
            return operations == null ? List.of() : operations;
        }
    }

    /**
     * An endpoint with its own price. {@code path} is an Ant pattern over the path after {@code /v1/egress/<name>};
     * {@code method} is optional and matches any method when absent.
     */
    public record Priced(String path, String method, String product, BigDecimal price) {
    }

    public Map<String, Route> egress() {
        return egress == null ? Map.of() : egress;
    }

    public Optional<Route> route(String name) {
        return Optional.ofNullable(egress().get(name));
    }
}
