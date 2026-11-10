package ai.costguard.core.software.internal;

import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.SoftwareLinks.ReportedInvoice;
import ai.costguard.core.software.SoftwareLinks.ReportedPayment;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Invoices and payments from the pipeline and the connectors (ADR 0033).
 *
 * <ul>
 *   <li>An invoice is for a subscription when its vendor has one, or when exactly one of the vendor's products is
 *       named in its lines.
 *   <li>A payment settles the invoice of its vendor in the same currency whose total it equals within 1%, issued
 *       from {@value #PAID_BEFORE_DAYS} days before the payment to {@value #PAID_AFTER_DAYS} days after it (an
 *       advance), the nearest in time. One invoice takes one payment; an agent's commission settles nothing.
 * </ul>
 */
@Component
class InvoiceBook {

    static final int PAID_BEFORE_DAYS = 120;
    static final int PAID_AFTER_DAYS = 30;
    private static final BigDecimal MATCH_SHARE = new BigDecimal("0.01");

    private final SoftwareRepository software;
    private final InvoiceRepository invoices;

    InvoiceBook(SoftwareRepository software, InvoiceRepository invoices) {
        this.software = software;
        this.invoices = invoices;
    }

    UUID invoiceSeen(UUID tenantId, ReportedInvoice r) {
        UUID subscription = subscriptionOf(r).orElse(null);
        InvoiceRepository.Stored stored = invoices.upsertInvoice(tenantId, r, subscription);
        invoices.replaceLines(tenantId, stored.id(), r.lines());
        settle(r.vendorId());
        return stored.id();
    }

    UUID paymentSeen(UUID tenantId, ReportedPayment p) {
        UUID id = invoices.upsertPayment(tenantId, p);
        settle(p.vendorId());
        return id;
    }

    private Optional<UUID> subscriptionOf(ReportedInvoice r) {
        List<Subscription> ofVendor = software.subscriptions().stream()
                .filter(s -> s.vendorId().equals(r.vendorId())).toList();
        if (ofVendor.size() == 1) {
            return Optional.of(ofVendor.getFirst().id());
        }
        List<String> texts = new ArrayList<>();
        r.lines().forEach(l -> texts.add(" " + Names.clean(l.description()) + " "));
        List<Subscription> named = ofVendor.stream()
                .filter(s -> {
                    String product = " " + Names.clean(s.product()) + " ";
                    return product.isBlank() ? false : texts.stream().anyMatch(t -> t.contains(product));
                })
                .toList();
        return named.size() == 1 ? Optional.of(named.getFirst().id()) : Optional.empty();
    }

    static String number(String number) {
        return number.toLowerCase(Locale.ROOT).replaceAll("[\\s№#]", "");
    }

    /** Ties the vendor's payments that settle nothing yet to its invoices nobody has paid. */
    private void settle(UUID vendorId) {
        List<Invoice> open = new ArrayList<>(invoices.invoicesOf(vendorId));
        Set<UUID> paid = invoices.paidInvoices(vendorId);
        open.removeIf(i -> paid.contains(i.id()));
        for (Payment p : invoices.unsettledPayments(vendorId)) {
            Invoice best = null;
            long bestDays = Long.MAX_VALUE;
            for (Invoice i : open) {
                long days = ChronoUnit.DAYS.between(i.issuedOn(), p.paidOn());
                boolean inTime = days >= -PAID_AFTER_DAYS && days <= PAID_BEFORE_DAYS;
                boolean same = i.currency().equals(p.currency())
                        && i.total().subtract(p.amount()).abs().compareTo(i.total().abs().multiply(MATCH_SHARE)) <= 0;
                if (inTime && same && Math.abs(days) < bestDays) {
                    best = i;
                    bestDays = Math.abs(days);
                }
            }
            if (best != null) {
                invoices.settle(p.id(), best.id());
                open.remove(best);
            }
        }
    }
}
