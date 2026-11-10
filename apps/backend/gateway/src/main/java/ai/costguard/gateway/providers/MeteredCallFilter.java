package ai.costguard.gateway.providers;

import ai.costguard.events.OperationKind;
import ai.costguard.gateway.answers.Answer;
import ai.costguard.gateway.answers.AnswerCache;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.egress.EgressPricing;
import ai.costguard.gateway.egress.EgressProperties;
import ai.costguard.gateway.egress.EgressRoutes;
import ai.costguard.gateway.ladder.Decisions;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.ladder.TaskGoal;
import ai.costguard.gateway.metering.CostEstimator;
import ai.costguard.gateway.metering.TokenCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Checks, reserves and settles every proxied call (board frame 2). Before the call: count input tokens, cap
 * {@code max_tokens}, run the ladder. After it: read the usage from the response, settle, record. A provider error
 * gives the reserve back and is recorded as denied with {@code upstream_error}. If the response carries no usage, the reserved amount is charged: it is the most the call
 * could cost.
 */
@Component
class MeteredCallFilter implements GlobalFilter, Ordered {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CAPTURE = 4 * 1024 * 1024;

    private final ProviderProperties properties;
    private final EgressProperties egress;
    private final EgressPricing egressPricing;
    private final ProviderCredentials credentials;
    private final Decisions decisions;
    private final BudgetReserve reserve;
    private final TokenCounter tokens;
    private final CostEstimator estimator;
    private final AnswerCache answers;
    private final Clock clock;

    MeteredCallFilter(ProviderProperties properties, EgressProperties egress, EgressPricing egressPricing,
            ProviderCredentials credentials, Decisions decisions, BudgetReserve reserve, TokenCounter tokens,
            CostEstimator estimator, AnswerCache answers, Clock clock) {
        this.properties = properties;
        this.egress = egress;
        this.egressPricing = egressPricing;
        this.credentials = credentials;
        this.decisions = decisions;
        this.reserve = reserve;
        this.tokens = tokens;
        this.estimator = estimator;
        this.answers = answers;
        this.clock = clock;
    }

    /**
     * Before NettyWriteResponseFilter: that filter writes the response to the exchange it was given, so a filter
     * after it never sees the response, and the reserve would never be settled.
     */
    @Override
    public int getOrder() {
        return org.springframework.cloud.gateway.filter.NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        Object provider = route.getMetadata().get(ProviderRoutes.PROVIDER);
        Object egress = route.getMetadata().get(EgressRoutes.EGRESS);
        if (provider == null && egress == null) {
            return chain.filter(exchange);
        }
        Optional<UUID> id = operationId(exchange.getRequest());
        if (id.isEmpty()) {
            return refuse(exchange, 400, "operation_id_required", null);
        }
        return DataBufferUtils.join(exchange.getRequest().getBody()).map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                }).defaultIfEmpty(new byte[0])
                .flatMap(body -> provider != null
                        ? llm(exchange, chain, id.get(), (String) provider, body)
                        : paid(exchange, chain, id.get(), (String) egress, body));
    }

    private static Optional<UUID> operationId(ServerHttpRequest request) {
        String key = request.getHeaders().getFirst("Idempotency-Key");
        if (key == null) {
            return Optional.of(UUID.randomUUID());
        }
        try {
            return Optional.of(UUID.fromString(key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String secret(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return request.getHeaders().getFirst("x-api-key");
    }

    private LocalDate today() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private Mono<Void> llm(ServerWebExchange exchange, GatewayFilterChain chain, UUID id, String provider, byte[] body) {
        ProviderProperties.Provider p = properties.providers().get(provider);
        Dialect dialect = Dialects.of(provider);
        JsonNode request;
        try {
            request = JSON.readTree(body);
        } catch (Exception e) {
            return refuse(exchange, 400, "invalid_request", null);
        }
        if (!(request instanceof ObjectNode json)) {
            return refuse(exchange, 400, "invalid_request", null);
        }
        String model = dialect.model(json);
        long input = tokens.count(p.vendor(), model, dialect.prompt(json));
        long maxOutput = dialect.maxTokens(json).orElse(properties.maxTokens());
        Operation op = new Operation(id, secret(exchange.getRequest()), exchange.getRequest().getHeaders()
                .getFirst("X-CostGuard-Task"), OperationKind.LLM_CALL, p.vendor(), model, BigDecimal.ZERO,
                clock.instant());
        op.tokens(input, maxOutput);
        op.taskGoal(TaskGoal.fromHeader(exchange.getRequest().getHeaders().getFirst(TaskGoal.HEADER)));
        return estimateThenDecide(op, exchange, () -> estimator.llm(op.tenantId(), p.vendor(), model, input, maxOutput,
                today()))
                .flatMap(passed -> {
                    if (!passed) {
                        return Mono.empty();
                    }
                    dialect.maxTokens(json, op.maxOutputTokens());
                    byte[] rewritten = json.toString().getBytes(StandardCharsets.UTF_8);
                    return forward(exchange, chain, op, rewritten,
                            headers -> credentials.apply(provider, p, headers),
                            response -> settleLlm(op, p.vendor(), dialect, response),
                            partial -> abortedLlm(op, p.vendor(), dialect, partial));
                });
    }

    /**
     * A paid API (ADR 0010): one call costs the price of its endpoint, reserved before the call and charged after it.
     * The product of the endpoint goes to {@code agent.operations.v1} as the model.
     */
    private Mono<Void> paid(ServerWebExchange exchange, GatewayFilterChain chain, UUID id, String name, byte[] body) {
        ServerHttpRequest request = exchange.getRequest();
        EgressProperties.Route route = egress.route(name).orElseThrow();
        EgressPricing.Endpoint endpoint = egressPricing.endpoint(name, request.getMethod().name(),
                EgressRoutes.apiPath(name, request.getPath().value())).orElseThrow();
        Operation op = new Operation(id, secret(request), request.getHeaders().getFirst("X-CostGuard-Task"),
                OperationKind.PAID_API, endpoint.vendor(), endpoint.product(), BigDecimal.ZERO, clock.instant());
        op.taskGoal(TaskGoal.fromHeader(request.getHeaders().getFirst(TaskGoal.HEADER)));
        return estimateThenDecide(op, exchange, () -> egressPricing.price(op.tenantId(), endpoint, today()))
                .flatMap(passed -> !passed ? Mono.empty() : forward(exchange, chain, op, body, headers -> {
                    EgressRoutes.credentials(route, headers);
                    return Mono.empty();
                }, response -> Mono.just(op.estimatedCost()), partial -> Mono.just(op.estimatedCost())));
    }

    /**
     * The price can only be looked up once the key tells whose catalog to use, so the ladder runs with the identity
     * step first and the estimate is filled in right after it.
     */
    private Mono<Boolean> estimateThenDecide(Operation op, ServerWebExchange exchange,
            java.util.function.Supplier<Optional<BigDecimal>> estimate) {
        return Mono.defer(() -> {
            OperationLookup lookup = new OperationLookup(op, estimate);
            return decisions.decide(lookup.prepared()).flatMap(result -> switch (result) {
                case StepResult.Pass pass -> Mono.just(true);
                case StepResult.Refuse refuse -> refuse(exchange, refuse.status(), refuse.type(), refuse.message(), null)
                        .thenReturn(false);
                case StepResult.RequireApproval held -> refuse(exchange, 403, "approval_required",
                        op.operationId().toString()).thenReturn(false);
                case StepResult.Answered answered -> replay(exchange, answered.answer()).thenReturn(false);
            });
        });
    }

    /** Fills in the estimate as soon as the identity is known, which is what the price lookup needs. */
    private final class OperationLookup {
        private final Operation op;
        private final java.util.function.Supplier<Optional<BigDecimal>> estimate;

        OperationLookup(Operation op, java.util.function.Supplier<Optional<BigDecimal>> estimate) {
            this.op = op;
            this.estimate = estimate;
        }

        Operation prepared() {
            op.onIdentified(() -> estimate.get());
            return op;
        }
    }

    private Mono<BigDecimal> settleLlm(Operation op, String vendor, Dialect dialect, byte[] response) {
        Optional<long[]> usage = usage(dialect, response);
        if (usage.isEmpty()) {
            return Mono.just(op.estimatedCost());
        }
        long[] u = usage.get();
        op.tokens(u[0], u[1]);
        return Mono.just(estimator.llm(op.tenantId(), vendor, op.model(), u[0], u[1], today()).orElse(op.estimatedCost()));
    }

    /**
     * The agent dropped the stream (board frame 2). The provider still charges what it sent, so the call is charged
     * for the input and for the output received so far: the usage if the stream got that far, else the tokens of the
     * text received, never more than the cap. The rest of the reserve goes back at once, not when the sweeper runs.
     */
    private Mono<BigDecimal> abortedLlm(Operation op, String vendor, Dialect dialect, byte[] partial) {
        Optional<long[]> usage = usage(dialect, partial);
        long output = usage.map(u -> u[1]).orElseGet(() -> Math.min(op.maxOutputTokens(),
                tokens.count(vendor, op.model(), streamedText(partial))));
        long input = usage.map(u -> u[0]).orElse(op.inputTokens());
        op.tokens(input, output);
        return Mono.just(estimator.llm(op.tenantId(), vendor, op.model(), input, output, today())
                .orElse(op.estimatedCost()));
    }

    /** The text of the {@code content} and {@code text} fields of the chunks received, for counting tokens. */
    static String streamedText(byte[] partial) {
        StringBuilder text = new StringBuilder();
        for (String line : new String(partial, StandardCharsets.UTF_8).split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            try {
                JsonNode chunk = JSON.readTree(line.substring(5).trim());
                chunk.findValues("content").forEach(v -> text.append(v.asText()));
                chunk.findValues("text").forEach(v -> text.append(v.asText()));
            } catch (Exception ignored) {
                // A cut chunk or [DONE] carries no text.
            }
        }
        return text.toString();
    }

    /** A plain JSON body, or Server-Sent Events whose last chunk with usage counts. */
    static Optional<long[]> usage(Dialect dialect, byte[] response) {
        String text = new String(response, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            try {
                return dialect.usage(JSON.readTree(text));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        Optional<long[]> last = Optional.empty();
        long[] merged = null;
        for (String line : text.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) {
                continue;
            }
            try {
                Optional<long[]> u = dialect.usage(JSON.readTree(data));
                if (u.isPresent()) {
                    long[] v = u.get();
                    merged = merged == null ? v.clone()
                            : new long[] {Math.max(merged[0], v[0]), Math.max(merged[1], v[1])};
                    last = Optional.of(merged);
                }
            } catch (Exception ignored) {
                // A chunk that is not JSON carries no usage.
            }
        }
        return last;
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, Operation op, byte[] body,
            java.util.function.Function<HttpHeaders, Mono<Void>> auth,
            java.util.function.Function<byte[], Mono<BigDecimal>> actualCost,
            java.util.function.Function<byte[], Mono<BigDecimal>> abortedCost) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(exchange.getRequest().getHeaders());
        headers.remove("Idempotency-Key");
        headers.remove("X-CostGuard-Task");
        headers.remove(TaskGoal.HEADER);
        headers.setContentLength(body.length);
        return auth.apply(headers).then(Mono.defer(() -> {
            ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public HttpHeaders getHeaders() {
                    return headers;
                }

                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
                }
            };
            Settlement settlement = new Settlement(exchange.getResponse(), op, actualCost, abortedCost);
            return chain.filter(exchange.mutate().request(request).response(settlement).build())
                    .onErrorResume(e -> settlement.fail().then(Mono.error(e)));
        }));
    }

    private final class Settlement extends ServerHttpResponseDecorator {

        private final Operation op;
        private final java.util.function.Function<byte[], Mono<BigDecimal>> actualCost;
        private final java.util.function.Function<byte[], Mono<BigDecimal>> abortedCost;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final AtomicBoolean done = new AtomicBoolean();

        Settlement(ServerHttpResponse delegate, Operation op,
                java.util.function.Function<byte[], Mono<BigDecimal>> actualCost,
                java.util.function.Function<byte[], Mono<BigDecimal>> abortedCost) {
            super(delegate);
            this.op = op;
            this.actualCost = actualCost;
            this.abortedCost = abortedCost;
        }

        private DataBuffer capture(DataBuffer buffer) {
            synchronized (captured) {
                if (captured.size() < MAX_CAPTURE) {
                    byte[] copy = new byte[buffer.readableByteCount()];
                    buffer.toByteBuffer().get(copy);
                    captured.write(copy, 0, copy.length);
                }
            }
            return buffer;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(Flux.from(body).map(this::capture).doOnCancel(this::abort))
                    .then(Mono.defer(this::finish));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(Flux.from(body).map(inner -> Flux.from(inner).map(this::capture))
                            .doOnCancel(this::abort))
                    .then(Mono.defer(this::finish));
        }

        /** The agent went away mid-stream: charge what was sent, give the rest of the reserve back now. */
        private void abort() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            byte[] partial;
            synchronized (captured) {
                partial = captured.toByteArray();
            }
            abortedCost.apply(partial)
                    .flatMap(actual -> reserve.settle(op.tenantId(), op.operationId().toString(), actual)
                            .doOnNext(ok -> decisions.allowed(op, actual, op.inputTokens(), op.maxOutputTokens())))
                    .onErrorResume(e -> Mono.just(false))
                    .subscribe();
        }

        /** The API failed: the reserve goes back, nothing is charged, and the failure is recorded (ADR 0010). */
        Mono<Void> fail() {
            if (!done.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return reserve.release(op.tenantId(), op.operationId().toString()).onErrorReturn(false)
                    .doOnNext(released -> decisions.failed(op, "upstream_error")).then();
        }

        private Mono<Void> finish() {
            HttpStatusCode status = getStatusCode();
            if (status != null && status.isError()) {
                return fail();
            }
            if (!done.compareAndSet(false, true)) {
                return Mono.empty();
            }
            byte[] body = captured.toByteArray();
            return actualCost.apply(body)
                    .flatMap(actual -> reserve.settle(op.tenantId(), op.operationId().toString(), actual)
                            .doOnNext(ok -> decisions.allowed(op, actual, op.inputTokens(), op.maxOutputTokens())))
                    .onErrorResume(e -> Mono.just(false))
                    .then(Mono.defer(() -> keep(body)));
        }

        /**
         * The answer as the agent got it, for a retry with the same key. A stream is kept whole and given back in one
         * piece; one cut by MAX_CAPTURE is not kept at all.
         */
        private Mono<Void> keep(byte[] body) {
            if (body.length >= MAX_CAPTURE || !answers.fits(body.length) || op.agent() == null) {
                return Mono.empty();
            }
            HttpStatusCode status = getStatusCode();
            MediaType type = getHeaders().getContentType();
            return answers.keep(op.tenantId(), op.operationId(), new Answer(status == null ? 200 : status.value(),
                            type == null ? null : type.toString(), body))
                    .onErrorReturn(false).then();
        }
    }

    /** A retry of an operation that went through: its first answer, checked and charged once. */
    private static Mono<Void> replay(ServerWebExchange exchange, Answer answer) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(answer.status()));
        if (answer.contentType() != null) {
            response.getHeaders().setContentType(MediaType.parseMediaType(answer.contentType()));
        }
        response.getHeaders().set("X-CostGuard-Replayed", "true");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(answer.body())));
    }

    private static Mono<Void> refuse(ServerWebExchange exchange, int status, String type, String approvalId) {
        return refuse(exchange, status, type, type, approvalId);
    }

    /** The OpenAI error shape. {@code message} is escaped for JSON: it is a sentence, not a code. */
    private static Mono<Void> refuse(ServerWebExchange exchange, int status, String type, String message,
            String approvalId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String text = (message == null ? type : message).replace("\\", "\\\\").replace("\"", "\\\"");
        String body = "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + text + "\""
                + (approvalId == null ? "" : ",\"approval_id\":\"" + approvalId + "\"") + "}}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
