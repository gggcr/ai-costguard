package ai.costguard.core.software.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice in the currency of the document. {@code dated} is false when the source named no date and
 * {@code issuedOn} is the day it arrived. {@code paymentId} is the payment that settled it, if one did.
 */
record Invoice(UUID id, UUID vendorId, UUID subscriptionId, String number, LocalDate issuedOn, boolean dated,
        String currency, BigDecimal total, String source, Instant receivedAt, UUID paymentId,
        List<InvoiceLine> lines) {
}

record InvoiceLine(int lineNo, String description, BigDecimal amount) {
}

/** A payment as the bank or the statement saw it; {@code invoiceId} is the invoice it settled, if one matched. */
record Payment(UUID id, UUID vendorId, UUID invoiceId, BigDecimal amount, String currency, LocalDate paidOn,
        boolean dated, String purpose, String kind, String card, String mcc, String role, String intermediary,
        String source, Instant receivedAt) {
}
