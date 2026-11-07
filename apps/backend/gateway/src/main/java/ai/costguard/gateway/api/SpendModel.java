package ai.costguard.gateway.api;

import java.math.BigDecimal;

/** Body of POST /v1/spend/authorize. The agent is not in the body: it comes from the virtual key. */
record AuthorizeRequest(String taskId, String kind, String vendor, String product, BigDecimal amount) {
}

record SettleRequest(String authorizationId, BigDecimal amount) {
}

record ReleaseRequest(String authorizationId) {
}

record Authorized(String authorizationId, String status, BigDecimal reserved) {
}

record Settled(String authorizationId, String status, BigDecimal amount) {
}

record ApprovalStatus(String approvalId, String status) {
}

/** The OpenAI error shape, so standard clients parse it as is. {@code approval_id} only for approval_required. */
record ErrorBody(Error error) {

    record Error(String type, String message, String approvalId) {
    }

    static ErrorBody of(String type) {
        return new ErrorBody(new Error(type, type, null));
    }
}
