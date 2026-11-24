package ai.costguard.core.shared.inbox;

import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.journal.OperationJournal;
import ai.costguard.core.journal.Receipts;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.wire.SingleObject;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Installs without Kafka ({@code EVENTS_TRANSPORT=direct}, ADR 0080): the gateway posts each operation here instead
 * of to {@code agent.operations.v1}. The inbox is the same, and safe to call again with the same operation, so the
 * gateway may resend what it is not sure arrived.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "costguard.events.transport", havingValue = "direct")
class DirectInboxConfig {

    @Bean
    OperationInbox operationInbox(OperationPosting ledger, Receipts receipts, ApprovalRequests approvals) {
        return new OperationInbox(ledger, receipts, approvals);
    }

    /** Behind the internal token (InternalSecurityConfig). Found by scanning too, so it has its own condition. */
    @Hidden
    @RestController
    @ConditionalOnProperty(name = "costguard.events.transport", havingValue = "direct")
    static class OperationController {

        private final OperationInbox inbox;
        private final OperationJournal journal;

        OperationController(OperationInbox inbox, OperationJournal journal) {
            this.inbox = inbox;
            this.journal = journal;
        }

        /**
         * The body is one AgentOperation in Avro's single-object encoding. It goes to the journal, where ClickHouse
         * would have it from the topic, then to the inbox. Both are safe to repeat, so a failure of the second is
         * answered 5xx and the gateway sends the operation again.
         */
        @PostMapping(path = "/internal/v1/gateway/operations", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        ResponseEntity<Void> operation(@RequestBody byte[] body) {
            AgentOperation op;
            try {
                op = SingleObject.decode(body, AgentOperation.class);
            } catch (RuntimeException notAnOperation) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            journal.record(new OperationJournal.Entry(op.getTenantId(), op.getOperationId(), op.getAgentId(),
                    op.getOccurredAt(), op.getKind().name(), op.getProvider(), op.getModel(), op.getDecision().name(),
                    op.getDenialCode(), op.getEstimatedCost(), op.getActualCost(), op.getCurrency(),
                    op.getInputTokens(), op.getOutputTokens()));
            inbox.handle(op);
            return ResponseEntity.noContent().build();
        }
    }
}
