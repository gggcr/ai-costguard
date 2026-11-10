package ai.costguard.core.software.internal;

import ai.costguard.core.software.SoftwareLinks.ReportedInvoice;
import ai.costguard.core.software.SoftwareLinks.ReportedPayment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvoiceRepository {

    private static final String INVOICE = """
            select i.*, (select p.id from payment p where p.invoice_id = i.id order by p.paid_on, p.id limit 1)
                        as payment_id
            from invoice i
            """;

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Stored(UUID id, boolean inserted) {
    }

    /** A correction keeps the subscription found before when the new data names none. */
    Stored upsertInvoice(UUID tenantId, ReportedInvoice r, UUID subscriptionId) {
        return jdbc.sql("""
                insert into invoice (id, tenant_id, entity_key, vendor_id, subscription_id, number, issued_on, dated,
                                     currency, total, source, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, entity_key) do update
                    set vendor_id = excluded.vendor_id,
                        subscription_id = coalesce(excluded.subscription_id, invoice.subscription_id),
                        number = excluded.number, issued_on = excluded.issued_on, dated = excluded.dated,
                        currency = excluded.currency, total = excluded.total, source = excluded.source
                returning id, (xmax = 0) as inserted
                """).params(UUID.randomUUID(), tenantId, r.entityKey(), r.vendorId(), subscriptionId, r.number(),
                r.issuedOn(), r.dated(), r.currency(), r.total(), r.source(), Timestamp.from(r.at()))
                .query((rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getBoolean("inserted"))).single();
    }

    void replaceLines(UUID tenantId, UUID invoiceId, List<ReportedInvoice.Line> lines) {
        jdbc.sql("delete from invoice_line where invoice_id = ?").param(invoiceId).update();
        int no = 0;
        for (ReportedInvoice.Line l : lines) {
            jdbc.sql("""
                    insert into invoice_line (tenant_id, invoice_id, line_no, description, amount)
                    values (?, ?, ?, ?, ?)
                    """).params(tenantId, invoiceId, ++no, l.description(), l.amount()).update();
        }
    }

    UUID upsertPayment(UUID tenantId, ReportedPayment p) {
        return jdbc.sql("""
                insert into payment (id, tenant_id, entity_key, vendor_id, amount, currency, paid_on, dated, purpose,
                                     kind, card, mcc, role, intermediary, source, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, entity_key) do update
                    set vendor_id = excluded.vendor_id, amount = excluded.amount, currency = excluded.currency,
                        paid_on = excluded.paid_on, dated = excluded.dated, purpose = excluded.purpose,
                        kind = excluded.kind, card = excluded.card, mcc = excluded.mcc, role = excluded.role,
                        intermediary = excluded.intermediary, source = excluded.source,
                        invoice_id = case when payment.vendor_id = excluded.vendor_id
                                           and payment.amount = excluded.amount
                                           and payment.currency = excluded.currency
                                          then payment.invoice_id end
                returning id
                """).params(UUID.randomUUID(), tenantId, p.entityKey(), p.vendorId(), p.amount(), p.currency(),
                p.paidOn(), p.dated(), p.purpose(), p.kind(), p.card(), p.mcc(), p.role(), p.intermediary(),
                p.source(), Timestamp.from(p.at())).query(UUID.class).single();
    }

    private List<InvoiceLine> lines(UUID invoiceId) {
        return jdbc.sql("select line_no, description, amount from invoice_line where invoice_id = ? order by line_no")
                .param(invoiceId)
                .query((rs, n) -> new InvoiceLine(rs.getInt("line_no"), rs.getString("description"),
                        rs.getBigDecimal("amount")))
                .list();
    }

    private Invoice invoice(ResultSet rs, boolean withLines) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Invoice(id, rs.getObject("vendor_id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getString("number"), rs.getObject("issued_on", LocalDate.class), rs.getBoolean("dated"),
                rs.getString("currency"), rs.getBigDecimal("total"), rs.getString("source"),
                rs.getTimestamp("received_at").toInstant(), rs.getObject("payment_id", UUID.class),
                withLines ? lines(id) : List.of());
    }

    Optional<Invoice> invoice(UUID id) {
        return jdbc.sql(INVOICE + " where i.id = ?").param(id).query((rs, n) -> invoice(rs, true)).optional();
    }

    List<Invoice> invoicesOf(UUID vendorId) {
        return jdbc.sql(INVOICE + " where i.vendor_id = ? order by i.issued_on, i.received_at, i.id").param(vendorId)
                .query((rs, n) -> invoice(rs, false)).list();
    }

    /** Newest first; either filter may be null. */
    List<Invoice> invoices(UUID vendorId, UUID subscriptionId, int limit) {
        return jdbc.sql(INVOICE + """
                 where (cast(:vendor as uuid) is null or i.vendor_id = :vendor)
                   and (cast(:subscription as uuid) is null or i.subscription_id = :subscription)
                 order by i.issued_on desc, i.received_at desc, i.id limit :limit
                """).param("vendor", vendorId).param("subscription", subscriptionId).param("limit", limit)
                .query((rs, n) -> invoice(rs, true)).list();
    }

    private static Payment payment(ResultSet rs) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getObject("paid_on", LocalDate.class), rs.getBoolean("dated"), rs.getString("purpose"),
                rs.getString("kind"), rs.getString("card"), rs.getString("mcc"), rs.getString("role"),
                rs.getString("intermediary"), rs.getString("source"), rs.getTimestamp("received_at").toInstant());
    }

    /** Newest first; the filter may be null. */
    List<Payment> payments(UUID vendorId, int limit) {
        return jdbc.sql("""
                select * from payment where (cast(:vendor as uuid) is null or vendor_id = :vendor)
                order by paid_on desc, received_at desc, id limit :limit
                """).param("vendor", vendorId).param("limit", limit).query((rs, n) -> payment(rs)).list();
    }

    /** Payments in a window, for the audit. */
    List<Payment> paymentsBetween(LocalDate from, LocalDate to) {
        return jdbc.sql("select * from payment where paid_on between ? and ? order by paid_on, id").params(from, to)
                .query((rs, n) -> payment(rs)).list();
    }

    /** Invoices in a window with their entity keys, for the audit. */
    List<Keyed> invoicesBetween(LocalDate from, LocalDate to) {
        return jdbc.sql(INVOICE + " where i.issued_on between ? and ? order by i.received_at, i.entity_key")
                .params(from, to).query((rs, n) -> new Keyed(rs.getString("entity_key"), invoice(rs, false))).list();
    }

    record Keyed(String entityKey, Invoice invoice) {
    }

    List<Payment> unsettledPayments(UUID vendorId) {
        return jdbc.sql("""
                select * from payment where vendor_id = ? and invoice_id is null and role <> 'commission'
                order by paid_on, id
                """).param(vendorId).query((rs, n) -> payment(rs)).list();
    }

    Set<UUID> paidInvoices(UUID vendorId) {
        return new HashSet<>(jdbc.sql("select distinct invoice_id from payment where vendor_id = ? and invoice_id is not null")
                .param(vendorId).query(UUID.class).list());
    }

    void settle(UUID paymentId, UUID invoiceId) {
        jdbc.sql("update payment set invoice_id = ? where id = ?").params(invoiceId, paymentId).update();
    }
}
