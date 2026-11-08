package ai.costguard.gateway.egress;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * {@code /v1/egress/<name>/...} goes to the paid API with the rest of the path. The metered call filter of the
 * providers package runs the same check ladder on it as on a model call: reserve, call, settle or release, record.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EgressProperties.class)
public class EgressRoutes {

    /** Route metadata: the name of the paid API. */
    public static final String EGRESS = "costguard.egress";
    public static final String PREFIX = "/v1/egress/";

    @Bean
    RouteLocator egressRouteLocator(RouteLocatorBuilder builder, EgressProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        properties.egress().forEach((name, e) -> routes.route("egress-" + name, r -> r
                .path(PREFIX + name + "/**")
                .filters(f -> f.stripPrefix(3))
                .metadata(EGRESS, name)
                .uri(e.url())));
        return routes.build();
    }

    /** The path the API sees: what follows {@code /v1/egress/<name>}. */
    public static String apiPath(String name, String requestPath) {
        String rest = requestPath.substring(Math.min(requestPath.length(), PREFIX.length() + name.length()));
        return rest.isEmpty() ? "/" : rest;
    }

    /** Takes the agent's credentials off and puts the API's key on. */
    public static void credentials(EgressProperties.Route route, HttpHeaders headers) {
        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove("x-api-key");
        new java.util.ArrayList<>(headers.headerNames()).stream()
                .filter(h -> h.regionMatches(true, 0, "X-CostGuard-", 0, 12)).forEach(headers::remove);
        if (route.header() != null && route.value() != null && !route.value().isBlank()) {
            headers.set(route.header(), route.value());
        }
    }
}
