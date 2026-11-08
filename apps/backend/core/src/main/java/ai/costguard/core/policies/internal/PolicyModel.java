package ai.costguard.core.policies.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum Effect { DENY, REQUIRE_APPROVAL }

enum Outcome { ALLOW, REQUIRE_APPROVAL, DENY }

record PolicyRule(UUID id, String name, String expression, Effect effect, boolean enabled, Instant updatedAt) {
}

record RuleDetails(@NotBlank String name, @NotBlank String expression, @NotNull Effect effect, boolean enabled) {
}

record Publication(long version, Instant publishedAt) {
}

/** One operation to try the rules on. */
record SampleOperation(
        @NotBlank String agentId,
        @NotBlank String kind,
        @NotBlank String vendor,
        String model,
        @DecimalMin("0") double cost,
        @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String time,
        @Min(1) @Max(7) long weekday) {
}

/** Without {@code rules} the current rules are tried, so a change can be checked before it is saved. */
record SimulationRequest(@Valid List<RuleDetails> rules, @NotNull @Size(max = 10000) List<@Valid SampleOperation> operations) {
}

record SimulatedOperation(int index, Outcome outcome, List<String> matchedRules) {
}

record SimulationResult(int total, int denied, int requireApproval, List<SimulatedOperation> operations) {
}
