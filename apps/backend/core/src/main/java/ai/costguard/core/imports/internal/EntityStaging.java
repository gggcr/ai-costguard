package ai.costguard.core.imports.internal;

import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.software.SoftwareLinks;
import ai.costguard.platform.tenant.TenantSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merge of vendors and people. Vendors are matched by INN, then by the cleaned name, and created when new:
 * a vendor needs nothing but a name. People are matched by e-mail, then by a unique full name, but never created,
 * because an employee needs a department only people know. A license is tied when its vendor, subscription and
 * person are all known; then its last activity moves forward. Everything else waits in ingest_entity.
 */
@Service
class EntityStaging implements NormalizedEntities {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final SoftwareLinks software;
    private final EmployeeDirectory people;

    EntityStaging(JdbcClient jdbc, TenantSession session, SoftwareLinks software, EmployeeDirectory people) {
        this.jdbc = jdbc;
        this.session = session;
        this.software = software;
        this.people = people;
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
            case "EMPLOYEE" -> person(text(p, "email"), text(p, "name")).orElse(null);
            case "INVOICE", "PAYMENT", "CONTRACT" -> text(p, "vendor")
                    .map(n -> software.vendor(tenantId, n, text(p, "inn").orElse(null))).orElse(null);
            case "SUBSCRIPTION" -> subscription(tenantId, p).orElse(null);
            case "LICENSE_ASSIGNMENT" -> license(tenantId, p).orElse(null);
            default -> null;
        };
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
