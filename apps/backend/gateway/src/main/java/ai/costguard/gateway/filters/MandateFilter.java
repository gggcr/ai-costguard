package ai.costguard.gateway.filters;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Step 2 of the check ladder: the mandate allows the model, vendor, amount and time.
 *
 * <p>Stub, not registered as a bean yet. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
public class MandateFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.error(new UnsupportedOperationException("MandateFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 2;
    }
}
