package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.agents.MandateNotarizations;
import ai.costguard.core.approvals.OperationConfirmations;
import ai.costguard.events.NotarizedKind;
import ai.costguard.events.NotaryConfirmation;
import ai.costguard.platform.errors.ApiException;
import ai.costguard.platform.errors.ProblemCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NotaryInboxTest {

    final List<String> seen = new ArrayList<>();
    boolean refuse;

    final MandateNotarizations mandates = (tenant, mandate, version, hash, tx, at) -> {
        if (refuse) {
            throw mismatch();
        }
        seen.add("mandate " + mandate + " v" + version + " " + tx);
    };

    final OperationConfirmations operations = (tenant, operation, hash, tx, at) -> {
        if (refuse) {
            throw mismatch();
        }
        seen.add("operation " + operation + " " + tx);
    };

    private static ApiException mismatch() {
        return new ProblemCode() {
            @Override
            public HttpStatus status() {
                return HttpStatus.CONFLICT;
            }

            @Override
            public String detail() {
                return "mismatch";
            }

            @Override
            public String name() {
                return "NOTARY_HASH_MISMATCH";
            }
        }.failure();
    }

    private static NotaryConfirmation confirmation(NotarizedKind kind, UUID subject, Integer version) {
        return NotaryConfirmation.newBuilder().setRequestId(UUID.randomUUID()).setTenantId(UUID.randomUUID())
                .setKind(kind).setSubjectId(subject).setMandateVersion(version).setSubjectHash("a".repeat(64))
                .setNearTxHash("TX").setConfirmedAt(Instant.now()).build();
    }

    @Test
    void mandatesGoToAgentsAndOperationsToApprovals() {
        UUID mandate = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        NotaryInbox inbox = new NotaryInbox(mandates, operations);

        inbox.handle(confirmation(NotarizedKind.MANDATE, mandate, 2));
        inbox.handle(confirmation(NotarizedKind.OPERATION, operation, null));

        assertThat(seen).containsExactly("mandate " + mandate + " v2 TX", "operation " + operation + " TX");
    }

    @Test
    void aMandateWithoutAVersionIsSkipped() {
        new NotaryInbox(mandates, operations).handle(confirmation(NotarizedKind.MANDATE, UUID.randomUUID(), null));
        assertThat(seen).isEmpty();
    }

    @Test
    void aRefusedConfirmationDoesNotStopTheInbox() {
        refuse = true;
        new NotaryInbox(mandates, operations).handle(confirmation(NotarizedKind.OPERATION, UUID.randomUUID(), null));
        assertThat(seen).isEmpty();
    }
}
