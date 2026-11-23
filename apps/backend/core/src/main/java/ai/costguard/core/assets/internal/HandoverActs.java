package ai.costguard.core.assets.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.EmployeeDirectory.CompanyCard;
import ai.costguard.core.org.EmployeeDirectory.EmployeeCard;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Makes the handover act of every issue, in the transaction of the issue: an issue without its act cannot commit,
 * and an act without its issue cannot either. The act is stored as rendered and a trigger keeps it unchanged.
 */
@Component
class HandoverActs {

    /** An act as stored. {@code sha256} is of {@code pdf}, so a printed and scanned copy can be told apart. */
    record StoredAct(UUID id, UUID assignmentId, String number, LocalDate issuedOn, String issuedBy, byte[] pdf,
            String sha256) {
    }

    private final JdbcClient jdbc;
    private final EmployeeDirectory people;
    private final Clock clock;

    HandoverActs(JdbcClient jdbc, EmployeeDirectory people, Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.clock = clock;
    }

    /** The act of an issue that was just inserted. The employee must exist: {@link AssetService} checked it. */
    String make(UUID tenantId, Asset asset, Assignment issued, EmployeeCard employee, String issuedBy) {
        Instant now = clock.instant();
        String number = nextNumber(tenantId, issued.issuedAt().getYear());
        CompanyCard company = people.company().orElse(null);
        byte[] pdf = HandoverActPdf.render(new HandoverActPdf.ActData(number, issued.issuedAt(),
                company == null ? null : company.name(), company == null ? null : company.currency(), employee,
                asset.model(), asset.type(), asset.inventoryNumber(), asset.serialNumber(), asset.cost(),
                issued.returnDue(), issuedBy, issued.id(), now,
                company == null ? ai.costguard.core.shared.names.Language.RU : company.language()));
        jdbc.sql("""
                insert into asset_act (id, tenant_id, assignment_id, number, issued_on, issued_by, pdf, sha256,
                                       created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), tenantId, issued.id(), number, issued.issuedAt(), issuedBy, pdf,
                sha256(pdf), Timestamp.from(now)).update();
        return number;
    }

    Optional<StoredAct> of(UUID assignmentId) {
        return jdbc.sql("""
                select id, assignment_id, number, issued_on, issued_by, pdf, sha256
                from asset_act where assignment_id = ?
                """).param(assignmentId)
                .query((rs, n) -> new StoredAct(rs.getObject("id", UUID.class),
                        rs.getObject("assignment_id", UUID.class), rs.getString("number"),
                        rs.getObject("issued_on", LocalDate.class), rs.getString("issued_by"), rs.getBytes("pdf"),
                        rs.getString("sha256")))
                .optional();
    }

    /** АВ-2026-0001, per tenant and year. A rolled-back issue rolls its number back too, so there are no gaps. */
    private String nextNumber(UUID tenantId, int year) {
        int last = jdbc.sql("""
                insert into asset_act_counter (tenant_id, year, last) values (?, ?, 1)
                on conflict (tenant_id, year) do update set last = asset_act_counter.last + 1
                returning last
                """).params(tenantId, year).query(Integer.class).single();
        return "АВ-%d-%04d".formatted(year, last);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
