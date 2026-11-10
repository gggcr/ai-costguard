package ai.costguard.core.imports.internal;

import ai.costguard.core.assets.AssetLinks;
import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.Staff;
import ai.costguard.core.software.SoftwareLinks;
import ai.costguard.platform.tenant.TenantSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merge of vendors and people. Vendors are matched by INN, then by the cleaned name, and created when new:
 * a vendor needs nothing but a name. People are matched by e-mail, then by a unique full name, but never created,
 * because an employee needs a department only people know. A license is tied when its vendor, subscription and
 * person are all known; then its last activity moves forward. A device is registered by its serial or
 * inventory number and issued to the holder the source names (with a handover act). An employee an HR system
 * reports as dismissed is dismissed. Everything else waits in ingest_entity.
 */
@Service
class EntityStaging implements NormalizedEntities {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final SoftwareLinks software;
    private final EmployeeDirectory people;
    private final AssetLinks assets;
    private final Staff staff;

    EntityStaging(JdbcClient jdbc, TenantSession session, SoftwareLinks software, EmployeeDirectory people,
            AssetLinks assets, Staff staff) {
        this.jdbc = jdbc;
        this.session = session;
        this.software = software;
        this.people = people;
        this.assets = assets;
        this.staff = staff;
    }

    @Override
    @Transactional
    public UUID received(UUID tenantId, UUID sourceId, String entityKey, String entityType, String payload,
            double confidence, Instant at) {
        session.bind(tenantId);
        var stored = jdbc.sql("select last_seen from ingest_entity where entity_key = ? for update").param(entityKey)
                .query(Timestamp.class).optional();
        if (stored.isPresent() && stored.get().toInstant().isAfter(at)) {
            return null;
        }
        JsonNode p = parse(payload);
        UUID linked = link(tenantId, entityType, p);
        if (linked != null && entityType.equals("INVOICE")) {
            invoice(tenantId, entityKey, linked, p, at);
        } else if (linked != null && entityType.equals("PAYMENT")) {
            payment(tenantId, entityKey, linked, p, at);
        }
        jdbc.sql("""
                insert into ingest_entity (tenant_id, entity_key, entity_type, source_id, payload, confidence,
                                           linked_id, first_seen, last_seen)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (tenant_id, entity_key) do update
                    set entity_type = excluded.entity_type, source_id = excluded.source_id,
                        payload = excluded.payload, confidence = excluded.confidence,
                        linked_id = coalesce(excluded.linked_id, ingest_entity.linked_id),
                        last_seen = excluded.last_seen
                """).params(tenantId, entityKey, entityType, sourceId, payload, confidence, linked,
                Timestamp.from(at), Timestamp.from(at)).update();
        return linked;
    }

    private UUID link(UUID tenantId, String type, JsonNode p) {
        return switch (type) {
            case "VENDOR" -> text(p, "name").map(n -> software.vendor(tenantId, n, text(p, "inn").orElse(null)))
                    .orElse(null);
            case "EMPLOYEE" -> employee(tenantId, p);
            case "ASSET" -> asset(tenantId, p);
            case "INVOICE", "PAYMENT", "CONTRACT" -> text(p, "vendor")
                    .map(n -> software.vendor(tenantId, n, text(p, "inn").orElse(null))).orElse(null);
            case "SUBSCRIPTION" -> subscription(tenantId, p).orElse(null);
            case "LICENSE_ASSIGNMENT" -> license(tenantId, p).orElse(null);
            case "ACTIVITY_EVENT" -> activity(tenantId, p).orElse(null);
            default -> null;
        };
    }

    /**
     * An invoice goes to the software module (ADR 0033). One without a total is no invoice; one without a date gets
     * the day it arrived, and says so.
     */
    private void invoice(UUID tenantId, String entityKey, UUID vendorId, JsonNode p, Instant at) {
        BigDecimal total = text(p, "total").flatMap(EntityStaging::decimal).orElse(null);
        if (total == null) {
            return;
        }
        Optional<LocalDate> stated = text(p, "date").flatMap(EntityStaging::day);
        List<SoftwareLinks.ReportedInvoice.Line> lines = new ArrayList<>();
        for (JsonNode line : p.path("lines")) {
            var amount = text(line, "amount").flatMap(EntityStaging::decimal);
            if (amount.isPresent()) {
                lines.add(new SoftwareLinks.ReportedInvoice.Line(text(line, "description").orElse(""), amount.get()));
            }
        }
        software.invoiceSeen(tenantId, new SoftwareLinks.ReportedInvoice(entityKey, vendorId,
                text(p, "number").orElse(""), stated.orElse(day(at)), stated.isPresent(), currency(p), total,
                text(p, "source").or(() -> text(p, "file_name")).orElse(""), lines, at));
    }

    private void payment(UUID tenantId, String entityKey, UUID vendorId, JsonNode p, Instant at) {
        BigDecimal amount = text(p, "amount").flatMap(EntityStaging::decimal).orElse(null);
        if (amount == null) {
            return;
        }
        Optional<LocalDate> stated = text(p, "date").flatMap(EntityStaging::day);
        software.paymentSeen(tenantId, new SoftwareLinks.ReportedPayment(entityKey, vendorId, amount, currency(p),
                stated.orElse(day(at)), stated.isPresent(), text(p, "purpose").orElse(""),
                text(p, "kind").orElse(""), text(p, "card").orElse(""), text(p, "mcc").orElse(""),
                text(p, "role").orElse("payment"), text(p, "intermediary").orElse(""),
                text(p, "source").orElse(""), at));
    }

    private static String currency(JsonNode p) {
        String code = text(p, "currency").map(c -> c.toUpperCase(Locale.ROOT)).orElse("RUB");
        return code.length() == 3 ? code : "RUB";
    }

    private static LocalDate day(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Only a source that keeps employment says {@code dismissed}: an HR system. A login switched off in a
     * directory ({@code active: false}) may be a holiday, so it dismisses nobody.
     */
    private UUID employee(UUID tenantId, JsonNode p) {
        UUID id = person(text(p, "email"), text(p, "name")).orElse(null);
        if (id != null && p.path("dismissed").asBoolean(false)) {
            staff.dismissedInHrSystem(tenantId, id);
        }
        return id;
    }

    private UUID asset(UUID tenantId, JsonNode p) {
        UUID holder = person(text(p, "holder_email"), text(p, "holder_name")).orElse(null);
        BigDecimal cost = text(p, "cost").flatMap(EntityStaging::decimal).orElse(null);
        return assets.reported(tenantId, new AssetLinks.Reported(text(p, "serial_number").orElse(null),
                text(p, "inventory_number").orElse(null), text(p, "model").orElse(null),
                text(p, "type").orElse(null), cost, holder, text(p, "source").orElse("connector")));
    }

    private static Optional<BigDecimal> decimal(String text) {
        try {
            return Optional.of(new BigDecimal(text.replace(" ", "").replace(',', '.')));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<UUID> subscription(UUID tenantId, JsonNode p) {
        var vendor = text(p, "vendor");
        var product = text(p, "product");
        if (vendor.isEmpty() || product.isEmpty()) {
            return Optional.empty();
        }
        return software.subscription(software.vendor(tenantId, vendor.get(), null), product.get());
    }

    private Optional<UUID> license(UUID tenantId, JsonNode p) {
        var subscription = subscription(tenantId, p);
        if (subscription.isEmpty()) {
            return Optional.empty();
        }
        String who = text(p, "employee").orElse("");
        var employee = person(Optional.of(who).filter(w -> w.contains("@")), Optional.of(who));
        if (employee.isEmpty()) {
            return Optional.empty();
        }
        LocalDate lastActive = text(p, "last_active").flatMap(EntityStaging::day).orElse(null);
        return software.licenseSeen(tenantId, subscription.get(), employee.get(), lastActive);
    }

    /**
     * A sign-in or use of a product (ADR 0042): vendor, product, the person by e-mail or name, and when. Kept for the
     * person the core knows; returns that person.
     */
    private Optional<UUID> activity(UUID tenantId, JsonNode p) {
        var vendor = text(p, "vendor");
        var product = text(p, "product");
        Optional<Instant> at = text(p, "at").flatMap(EntityStaging::moment);
        String who = text(p, "email").or(() -> text(p, "employee")).orElse("");
        var employee = person(Optional.of(who).filter(w -> w.contains("@")), Optional.of(who));
        if (vendor.isEmpty() || product.isEmpty() || at.isEmpty() || employee.isEmpty()) {
            return Optional.empty();
        }
        software.activitySeen(tenantId, software.vendor(tenantId, vendor.get(), null), product.get(), employee.get(),
                at.get(), text(p, "kind").orElse("sign_in"), text(p, "source").orElse(""));
        return employee;
    }

    private static Optional<Instant> moment(String text) {
        try {
            return Optional.of(text.length() <= 10 ? LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : java.time.OffsetDateTime.parse(text).toInstant());
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Optional<UUID> person(Optional<String> email, Optional<String> name) {
        return email.flatMap(people::employeeByEmail).or(() -> name.flatMap(people::employeeByName));
    }

    private static Optional<String> text(JsonNode p, String field) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? Optional.empty() : Optional.of(v.asText().strip());
    }

    private static Optional<LocalDate> day(String text) {
        try {
            return Optional.of(LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (IOException e) {
            throw new IllegalArgumentException("Payload is not JSON", e);
        }
    }
}
