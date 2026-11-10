package ai.costguard.core.findings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A new invoice for a subscription, with the earlier invoices of that subscription to compare it with. Amounts are
 * in roubles, as the savings of findings are.
 *
 * @param label how people know the invoice: "№ 17 от 2026-11-05"
 * @param yearly the subscription is paid for a year at a time, so one invoice holds twelve months of savings
 * @param earlier the subscription's earlier invoices, newest first; duplicates of this one left out
 */
public record InvoiceEvidence(UUID subscriptionId, UUID invoiceId, String label, LocalDate issuedOn, BigDecimal total,
        boolean yearly, List<Earlier> earlier) {

    public record Earlier(LocalDate issuedOn, BigDecimal total) {
    }
}
