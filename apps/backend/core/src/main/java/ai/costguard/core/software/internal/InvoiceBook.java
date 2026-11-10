package ai.costguard.core.software.internal;

import ai.costguard.core.catalog.ExchangeRates;
import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.findings.InvoiceEvidence;
import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.SoftwareLinks.ReportedInvoice;
import ai.costguard.core.software.SoftwareLinks.ReportedPayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 *   <li>A new invoice for a subscription goes to the findings in progress on it, with the subscription's earlier
 *       invoices in roubles. A second copy of an invoice already seen (the same number) goes nowhere: it would
 *       show no saving and count as a miss.
 * </ul>
 */
@Component
class InvoiceBook {

    static final int PAID_BEFORE_DAYS = 120;
    static final int PAID_AFTER_DAYS = 30;
    private static final BigDecimal MATCH_SHARE = new BigDecimal("0.01");

    private final SoftwareRepository software;
    private final InvoiceRepository invoices;
    private final ExchangeRates rates;
    private final FindingsIntake findings;

    InvoiceBook(SoftwareRepository software, InvoiceRepository invoices, ExchangeRates rates,
            FindingsIntake findings) {
        this.software = software;
        this.invoices = invoices;
        this.rates = rates;
        this.findings = findings;
    }

    UUID invoiceSeen(UUID tenantId, ReportedInvoice r) {
        UUID subscription = subscriptionOf(r).orElse(null);
        InvoiceRepository.Stored stored = invoices.upsertInvoice(tenantId, r, subscription);
        invoices.replaceLines(tenantId, stored.id(), r.lines());
        settle(r.vendorId());
        if (stored.inserted()) {
            Invoice invoice = invoices.invoice(stored.id()).orElseThrow();
            if (invoice.subscriptionId() != null && !copyOfAnother(invoice)) {
                evidence(invoice).ifPresent(e -> findings.invoiced(tenantId, e));
            }
        }
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

    private boolean copyOfAnother(Invoice invoice) {
        return !invoice.number().isBlank() && invoices.invoicesOf(invoice.vendorId()).stream()
                .anyMatch(o -> !o.id().equals(invoice.id()) && number(o.number()).equals(number(invoice.number())));
    }

    private Optional<InvoiceEvidence> evidence(Invoice invoice) {
        Optional<BigDecimal> total = rub(invoice.total(), invoice.currency(), invoice.issuedOn());
        Subscription subscription = software.subscription(invoice.subscriptionId()).orElse(null);
        if (total.isEmpty() || subscription == null) {
            return Optional.empty();
        }
        Set<String> numbers = new HashSet<>();
        List<InvoiceEvidence.Earlier> earlier = new ArrayList<>();
        invoices.invoicesOf(invoice.vendorId()).stream()
                .filter(o -> invoice.subscriptionId().equals(o.subscriptionId()) && !o.id().equals(invoice.id()))
                .filter(o -> o.issuedOn().isBefore(invoice.issuedOn())
                        || o.issuedOn().equals(invoice.issuedOn()) && o.receivedAt().isBefore(invoice.receivedAt()))
                .sorted(Comparator.comparing(Invoice::issuedOn).thenComparing(Invoice::receivedAt).reversed())
                .forEach(o -> {
                    // copies of one invoice count once
                    if (!o.number().isBlank() && !numbers.add(number(o.number()))) {
                        return;
                    }
                    rub(o.total(), o.currency(), o.issuedOn())
                            .ifPresent(t -> earlier.add(new InvoiceEvidence.Earlier(o.issuedOn(), t)));
                });
        String label = (invoice.number().isBlank() ? "" : "№ " + invoice.number() + " ") + "от " + invoice.issuedOn();
        return Optional.of(new InvoiceEvidence(invoice.subscriptionId(), invoice.id(), label, invoice.issuedOn(),
                total.get(), subscription.billingPeriod() == BillingPeriod.year, earlier));
    }

    private Optional<BigDecimal> rub(BigDecimal amount, String currency, LocalDate day) {
        return currency.equals("RUB") || currency.equals("RUR") ? Optional.of(amount)
                : rates.toRub(amount, currency, day);
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
