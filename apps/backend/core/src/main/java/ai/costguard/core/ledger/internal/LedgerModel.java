package ai.costguard.core.ledger.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum BudgetLevel { company, department, agent }

enum EntryKind { charge, reversal }

/** {@code spent} is the sum of the ledger. What is reserved right now lives in the gateway's Redis. */
record Budget(UUID id, String name, BudgetLevel level, UUID refId, UUID parentId, BigDecimal limit, String currency,
        BigDecimal spent, long version) {
}

record NewBudget(
        @NotBlank String name,
        @NotNull BudgetLevel level,
        UUID refId,
        UUID parentId,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal limit,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}

record LimitChange(@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal limit) {
}

record LedgerEntry(UUID id, UUID budgetId, UUID operationId, EntryKind kind, BigDecimal amount, Instant occurredAt) {
}

record LedgerPage(List<LedgerEntry> items, int page, int size) {
}
