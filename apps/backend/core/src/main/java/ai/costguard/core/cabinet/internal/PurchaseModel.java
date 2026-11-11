package ai.costguard.core.cabinet.internal;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

enum PurchasePeriod { once, month, year }

record PurchaseDecision(boolean approve, @Size(max = 1000) String comment) {
}

/**
 * {@code status}: pending (finance decides), approved, rejected, has_license (turned down at once: the company has
 * this program; {@code licenseStatus} says whether the license was given from the pool or waits for IT), cancelled.
 */
record PurchaseRequest(UUID id, UUID employeeId, String employeeName, String product, String vendor,
        BigDecimal amount, String currency, String period, String reason, String fileName, String status,
        String matchedProduct, String licenseStatus, String decidedBy, Instant decidedAt, String decisionComment,
        Instant createdAt) {
}
