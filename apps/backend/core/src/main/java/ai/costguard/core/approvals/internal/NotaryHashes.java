package ai.costguard.core.approvals.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The hashes of an approved operation that the NEAR notary confirms (ADR 0009). Each is sha256, lowercase hex, of
 *
 * <pre>
 * costguard:operation:v1\n{tenant id}\n{operation id}\n{agent id}\n{amount, 6 decimals}\n{approval id}
 * costguard:approver:v1\n{tenant id}\n{approval id}\n{who decided}
 * </pre>
 *
 * The tenant id and the approval id never reach the chain, so nobody outside the company can tell from the
 * approver hash who approved, or test a guess.
 */
final class NotaryHashes {

    private NotaryHashes() {
    }

    static String operation(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, UUID approvalId) {
        return sha256("costguard:operation:v1", tenantId.toString(), operationId.toString(), agentId.toString(),
                amount.setScale(6, RoundingMode.HALF_EVEN).toPlainString(), approvalId.toString());
    }

    static String approver(UUID tenantId, UUID approvalId, String decidedBy) {
        return sha256("costguard:approver:v1", tenantId.toString(), approvalId.toString(), decidedBy);
    }

    private static String sha256(String... lines) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
