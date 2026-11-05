package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckLadder;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Runs the check ladder on every proxied provider call. A failed check is a refusal, never a pass. */
@Component
public class CheckLadderFilter implements GlobalFilter, Ordered {

    private final CheckLadder ladder;

    public CheckLadderFilter(CheckLadder ladder) {
        this.ladder = ladder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        OperationContext op = toOperation(exchange);
        return ladder.run(op)
                .onErrorReturn(new StepResult.Refuse(503, "check_unavailable"))
                .flatMap(result -> switch (result) {
                    // TODO: settle the reserve after the response; release it on a provider error
                    case StepResult.Pass pass -> chain.filter(exchange);
                    case StepResult.Refuse refuse -> refuse(exchange, refuse.status(), refuse.type());
                    case StepResult.RequireApproval approval -> refuse(exchange, 403, "approval_required");
                });
    }

    /** TODO: agent from the virtual key, model and price estimate from the request body. */
    private OperationContext toOperation(ServerWebExchange exchange) {
        String operationId = exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
        return new OperationContext(
                operationId != null ? operationId : exchange.getRequest().getId(),
                null, null, "model_call", null, null, null);
    }

    /** Error body in the OpenAI format, so standard clients parse it as is. */
    private Mono<Void> refuse(ServerWebExchange exchange, int status, String type) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + type + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return 0; // before routing to the provider
    }
}
