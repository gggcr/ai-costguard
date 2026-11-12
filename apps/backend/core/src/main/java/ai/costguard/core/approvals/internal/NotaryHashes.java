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
 * costguard:approver:v2\n{tenant id}\n{approval id}\n{account id of who decided}
 * </pre>
 *
 * The tenant id and the approval id never reach the chain, so nobody outside the company can tell from the
 * approver hash who approved, or test a guess.
 *
 * <p>v1 took who decided as the core had it: the token's e-mail, or for an agent owner in the cabinet the token's
 * name, which a person can change in Keycloak (finding H2 of 2026-11-12). v2 takes the id of the CostGuard account of
 * the token's subject, which they cannot. The first line of each format names it, so a hash of one format never
 * stands for another, nor for an operation hash; every field is a UUID or a fixed prefix, so the lines cannot run
 * into each other. Hashes already on the chain stay v1 and are checked as v1 ({@link #approverMatches}).
 */
final class NotaryHashes {

    private NotaryHashes() {
    }

    static String operation(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, UUID approvalId) {
        return sha256("costguard:operation:v1", tenantId.toString(), operationId.toString(), agentId.toString(),
                amount.setScale(6, RoundingMode.HALF_EVEN).toPlainString(), approvalId.toString());
    }

    /** The format of the approvals notarised before V49; only to check them, never for a new one. */
    static String approverV1(UUID tenantId, UUID approvalId, String decidedBy) {
        return sha256("costguard:approver:v1", tenantId.toString(), approvalId.toString(), decidedBy);
    }

    static String approverV2(UUID tenantId, UUID approvalId, UUID deciderAccountId) {
        return sha256("costguard:approver:v2", tenantId.toString(), approvalId.toString(),
                deciderAccountId.toString());
    }

    /**
     * True when {@code hash} is the approver hash of this approval in the given format: v1 from {@code decidedBy} as
     * stored when it was decided, v2 from the decider's account.
     */
    static boolean approverMatches(int version, UUID tenantId, UUID approvalId, String decidedBy,
            UUID deciderAccountId, String hash) {
        if (hash == null) {
            return false;
        }
        return switch (version) {
            case 1 -> decidedBy != null && approverV1(tenantId, approvalId, decidedBy).equals(hash);
            case 2 -> deciderAccountId != null && approverV2(tenantId, approvalId, deciderAccountId).equals(hash);
            default -> false;
        };
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
