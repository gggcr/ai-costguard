package ai.costguard.gateway.providers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code /v1/providers/<name>/...} goes to the provider's API with the rest of the path. Paid APIs have their routes
 * in {@link ai.costguard.gateway.egress.EgressRoutes}. {@link MeteredCallFilter} does the checks on both.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderRoutes {

    static final String PROVIDER = "costguard.provider";

    @Bean
    RouteLocator meteredRoutes(RouteLocatorBuilder builder, ProviderProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        if (properties.providers() != null) {
            properties.providers().forEach((name, p) -> routes.route("provider-" + name, r -> r
                    .path("/v1/providers/" + name + "/**")
                    .filters(f -> f.stripPrefix(3))
                    .metadata(PROVIDER, name)
                    .uri(p.url())));
        }
        return routes.build();
    }
}
