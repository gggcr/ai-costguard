package ai.costguard.gateway.api;

import ai.costguard.events.OperationKind;
import ai.costguard.gateway.approvals.ApprovalBoard;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.ladder.Decisions;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Spend API for purchases, paid APIs and cloud: the same check ladder as for model calls. The caller reserves,
 * spends, then settles the actual amount or releases the reserve. A reserve nobody settles is released after its
 * hold by the sweeper.
 */
@RestController
@RequestMapping("/v1/spend")
@Tag(name = "Spend", description = "Authorize, settle and release spending of an agent")
public class SpendController {

    private final Decisions decisions;
    private final BudgetReserve reserve;
    private final AgentDirectory agents;
    private final ApprovalBoard approvals;
    private final Clock clock;

    public SpendController(Decisions decisions, BudgetReserve reserve, AgentDirectory agents, ApprovalBoard approvals,
            Clock clock) {
        this.decisions = decisions;
        this.reserve = reserve;
        this.agents = agents;
        this.approvals = approvals;
        this.clock = clock;
    }

    static String secret(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<Object> error(int status, String type) {
        return ResponseEntity.status(status).body(ErrorBody.of(type));
    }

    @PostMapping("/authorize")
    @io.swagger.v3.oas.annotations.Operation(summary = "Run the check ladder and reserve the amount",
            description = "Idempotency-Key is the operation id, a UUID. A retry of an approved operation uses the same key.")
    public Mono<ResponseEntity<Object>> authorize(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Parameter(description = "Operation id, a UUID") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AuthorizeRequest request) {
        Optional<UUID> id = uuid(idempotencyKey);
        if (id.isEmpty()) {
            return Mono.just(error(400, "operation_id_required"));
        }
        if (request.amount() == null || request.amount().signum() < 0 || request.vendor() == null) {
            return Mono.just(error(400, "invalid_request"));
        }
        OperationKind kind = "llm_call".equalsIgnoreCase(request.kind()) ? OperationKind.LLM_CALL : OperationKind.PAID_API;
        Operation op = new Operation(id.get(), secret(authorization), request.taskId(), kind, request.vendor(),
                request.product(), request.amount(), clock.instant());
        return decisions.decide(op).map(result -> switch (result) {
            case StepResult.Pass pass -> ResponseEntity.ok(
                    new Authorized(op.operationId().toString(), "authorized", op.estimatedCost()));
            case StepResult.Refuse refuse -> error(refuse.status(), refuse.type());
            case StepResult.RequireApproval held -> ResponseEntity.status(403).body(new ErrorBody(
                    new ErrorBody.Error("approval_required", held.reason(), op.operationId().toString())));
        });
    }

    @PostMapping("/settle")
    @io.swagger.v3.oas.annotations.Operation(summary = "Charge the actual amount and return the rest of the reserve",
            description = "Repeating it is safe.")
    public Mono<ResponseEntity<Object>> settle(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SettleRequest request) {
        var agent = agents.byKey(Optional.ofNullable(secret(authorization)).orElse(""));
        Optional<UUID> id = uuid(request.authorizationId());
        if (agent.isEmpty()) {
            return Mono.just(error(401, "invalid_api_key"));
        }
        if (id.isEmpty() || request.amount() == null || request.amount().signum() < 0) {
            return Mono.just(error(400, "invalid_request"));
        }
        Operation op = new Operation(id.get(), null, null, OperationKind.PAID_API, "spend", null, request.amount(),
                clock.instant());
        op.agent(agent.get());
        return reserve.settle(op.tenantId(), id.get().toString(), request.amount()).map(ok -> {
            if (!ok) {
                return error(409, "reservation_unknown");
            }
            decisions.allowed(op, request.amount(), null, null);
            return ResponseEntity.ok(new Settled(id.get().toString(), "settled", request.amount()));
        });
    }

    @PostMapping("/release")
    @io.swagger.v3.oas.annotations.Operation(summary = "Give the whole reserve back")
    public Mono<ResponseEntity<Object>> release(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ReleaseRequest request) {
        var agent = agents.byKey(Optional.ofNullable(secret(authorization)).orElse(""));
        Optional<UUID> id = uuid(request.authorizationId());
        if (agent.isEmpty()) {
            return Mono.just(error(401, "invalid_api_key"));
        }
        if (id.isEmpty()) {
            return Mono.just(error(400, "invalid_request"));
        }
        return reserve.release(agent.get().getTenantId(), id.get().toString())
                .map(released -> ResponseEntity.ok(new Settled(id.get().toString(),
                        released ? "released" : "nothing_reserved", BigDecimal.ZERO)));
    }

    @GetMapping("/approvals/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Whether a held operation was decided",
            description = "approved means: retry the operation with the same Idempotency-Key.")
    public ResponseEntity<Object> approval(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {
        var agent = agents.byKey(Optional.ofNullable(secret(authorization)).orElse(""));
        if (agent.isEmpty()) {
            return error(401, "invalid_api_key");
        }
        Optional<UUID> operation = uuid(id);
        if (operation.isEmpty()) {
            return error(400, "invalid_request");
        }
        String status = approvals.decision(operation.get())
                .filter(d -> d.getAgentId().equals(agent.get().getAgentId()))
                .map(d -> d.getStatus().name().toLowerCase(Locale.ROOT)).orElse("pending");
        return ResponseEntity.ok(new ApprovalStatus(id, status));
    }
}
