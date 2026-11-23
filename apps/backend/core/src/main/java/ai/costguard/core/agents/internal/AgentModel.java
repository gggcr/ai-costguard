package ai.costguard.core.agents.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum AgentStatus { active, suspended }

enum KeyStatus { active, revoked }

enum MandateStatus { draft, published }

record Agent(UUID id, String name, String description, String framework, UUID departmentId, UUID ownerId,
        AgentStatus status, int riskScore, String riskReason, UUID mandateId, Instant createdAt) {
}

record AgentDetails(
        @NotBlank String name,
        @NotNull String description,
        @NotBlank String framework,
        @NotNull UUID departmentId,
        @NotNull UUID ownerId,
        @NotNull UUID mandateId) {
}

record VirtualKey(UUID id, UUID agentId, String prefix, Instant createdAt, KeyStatus status) {
}

/** The only response that carries the secret. */
record IssuedKey(VirtualKey key, String secret) {
}

/**
 * {@code mandateHash} and {@code nearTxHash} belong to the published version: the hash registered in NEAR (ADR 0009)
 * and the transaction that registered it. Both are null before the first publication; the transaction is null
 * until the notary reports it.
 */
record Mandate(UUID id, String name, List<String> models, List<String> vendors, BigDecimal maxPerOperation,
        BigDecimal approvalThreshold, String hours, MandateStatus status, int version, Instant updatedAt,
        String mandateHash, String nearTxHash) {
}

record MandateDetails(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> models,
        @NotEmpty List<@NotBlank String> vendors,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal maxPerOperation,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal approvalThreshold,
        @NotNull @Pattern(regexp = MandateExpression.HOURS) String hours) {

    MandateDetails {
        hours = MandateExpression.canonical(hours);
    }
}

/** What the gateway enforces. Empty fields mean the mandate was never published. */
record PublishedTerms(int version, String expression, BigDecimal approvalThreshold) {
}

/**
 * Who is calling. {@code restricted} is true for an agent owner, who may touch only their own agents; {@code subject}
 * is the token's sub, which finds the caller's account and employee (never the token's e-mail).
 */
record Actor(UUID tenantId, boolean restricted, String subject) {
}
