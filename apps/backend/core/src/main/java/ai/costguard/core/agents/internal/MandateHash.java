package ai.costguard.core.agents.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The hash of a published mandate version that the NEAR notary registers (ADR 0009): sha256, lowercase hex, of
 *
 * <pre>
 * costguard:mandate:v1\n{tenant id}\n{mandate id}\n{version}\n{approval threshold, 6 decimals}\n{CEL expression}
 * </pre>
 *
 * The tenant id is not on the chain, so nobody outside the company can guess the terms from the hash.
 */
final class MandateHash {

    private MandateHash() {
    }

    static String of(UUID tenantId, UUID mandateId, int version, BigDecimal approvalThreshold, String expression) {
        String canonical = String.join("\n", "costguard:mandate:v1", tenantId.toString(), mandateId.toString(),
                Integer.toString(version), approvalThreshold.setScale(6, RoundingMode.HALF_EVEN).toPlainString(),
                expression);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
