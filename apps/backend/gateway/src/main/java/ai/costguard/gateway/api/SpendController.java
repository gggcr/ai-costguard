package ai.costguard.gateway.api;

import ai.costguard.gateway.ladder.CheckLadder;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Spend API for purchases, paid APIs and cloud: the same check ladder as for model calls. */
@RestController
@RequestMapping("/v1/spend")
public class SpendController {

    private final CheckLadder ladder;

    public SpendController(CheckLadder ladder) {
        this.ladder = ladder;
    }

    @PostMapping("/authorize")
    public Mono<ResponseEntity<Map<String, Object>>> authorize(
            @RequestBody AuthorizeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        String operationId = idempotencyKey != null ? idempotencyKey : request.operationId();
        if (operationId == null) {
            return Mono.just(ResponseEntity.badRequest().body(error("operation_id_required")));
        }

        // TODO: agent from the virtual key
        OperationContext op = new OperationContext(
                operationId, null, request.taskId(), request.kind(), request.vendor(), null, request.amount());

        return ladder.run(op)
                .onErrorReturn(new StepResult.Refuse(503, "check_unavailable"))
                .map(result -> switch (result) {
                    case StepResult.Pass pass -> ResponseEntity.ok(
                            Map.<String, Object>of("authorization_id", operationId, "status", "authorized"));
                    case StepResult.Refuse refuse -> ResponseEntity.status(refuse.status()).body(error(refuse.type()));
                    case StepResult.RequireApproval approval -> ResponseEntity.status(403).body(error("approval_required"));
                });
    }

    // TODO: POST /v1/spend/settle and GET /v1/spend/approvals/{id}

    private static Map<String, Object> error(String type) {
        return Map.of("error", Map.of("type", type, "message", type));
    }
}
