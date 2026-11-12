package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.MicrosoftApps;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.shared.crypto.Sealer;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Доступ у поставщика (ADR 0065): when a seat goes to a person or back into the pool, the program itself follows —
 * through the company's Entra ID (assign / remove the enterprise app; Entra provisions the vendor) or the vendor's
 * SCIM API. After the commit, outside the transaction; every attempt goes into access_change, a failure also tells
 * owner and IT through the activity log, and the seat in CostGuard stays as decided.
 */
@Service
class VendorAccess {

    private static final Logger log = LoggerFactory.getLogger(VendorAccess.class);

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final EmployeeDirectory employees;
    private final MicrosoftApps microsoft;
    private final ScimClient scim;
    private final Sealer sealer;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    VendorAccess(JdbcClient jdbc, TenantSession session, TransactionTemplate tx, EmployeeDirectory employees,
            MicrosoftApps microsoft, ScimClient scim, Sealer sealer, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.session = session;
        this.tx = tx;
        this.employees = employees;
        this.microsoft = microsoft;
        this.scim = scim;
        this.sealer = sealer;
        this.events = events;
        this.clock = clock;
    }

    private static String aad(UUID tenantId, UUID subscriptionId) {
        return tenantId + ":scim:" + subscriptionId;
    }

    private record Setup(String kind, String entraAppId, String scimUrl, byte[] scimToken, String scimRevoke) {
    }

    private record Target(Setup setup, EmployeeDirectory.EmployeeCard person) {
    }

    @ApplicationModuleListener
    void on(SeatChanged e) {
        Optional<Target> target = tx.execute(s -> {
            session.bind(e.tenantId());
            return jdbc.sql("""
                            select kind, entra_app_id, scim_url, scim_token, scim_revoke from subscription_access
                            where subscription_id = ?""").param(e.subscriptionId())
                    .query((rs, i) -> new Setup(rs.getString("kind"), rs.getString("entra_app_id"),
                            rs.getString("scim_url"), rs.getBytes("scim_token"), rs.getString("scim_revoke")))
                    .optional()
                    .flatMap(setup -> employees.card(e.employeeId()).map(p -> new Target(setup, p)));
        });
        if (target.isEmpty() || target.get().person().email() == null) {
            return;
        }
        Setup setup = target.get().setup();
        EmployeeDirectory.EmployeeCard person = target.get().person();
        String status = "done";
        String detail;
        try {
            if ("entra".equals(setup.kind())) {
                if (e.granted()) {
                    microsoft.assign(e.tenantId(), setup.entraAppId(), person.email());
                    detail = "назначен в Microsoft Entra";
                } else {
                    microsoft.unassign(e.tenantId(), setup.entraAppId(), person.email());
                    detail = "снят в Microsoft Entra, поставщик получит удаление";
                }
            } else {
                String token = sealer.open(aad(e.tenantId(), e.subscriptionId()), setup.scimToken());
                String[] name = person.name().strip().split("\\s+", 2);
                detail = "SCIM: " + (e.granted()
                        ? scim.grant(setup.scimUrl(), token, person.email(), name[0], name.length > 1 ? name[1] : "")
                        : scim.revoke(setup.scimUrl(), token, person.email(), "delete".equals(setup.scimRevoke())));
            }
        } catch (MicrosoftApps.Unavailable | ScimClient.Failed | IllegalStateException x) {
            status = "failed";
            detail = x.getMessage();
            log.warn("Vendor access of subscription {} failed: {}", e.subscriptionId(), x.getMessage());
        }
        final String st = status;
        final String d = detail;
        tx.executeWithoutResult(s -> {
            session.bind(e.tenantId());
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into access_change (id, tenant_id, subscription_id, employee_id, action, status, detail, at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, e.tenantId(), e.subscriptionId(), e.employeeId(), e.granted() ? "grant" : "revoke",
                            st, d, Timestamp.from(clock.instant())).update();
            events.publishEvent(ActionTaken.of(e.tenantId(), "vendor.access." + (e.granted() ? "grant" : "revoke")
                    + ("done".equals(st) ? "" : ".failed"), "access_change", id,
                    Map.of("subscriptionId", e.subscriptionId(), "employeeId", e.employeeId())));
        });
    }

    SubscriptionAccess get(UUID tenantId, UUID subscriptionId) {
        return tx.execute(s -> {
            session.bind(tenantId);
            List<AccessChange> recent = jdbc.sql("""
                            select id, employee_id, action, status, detail, at from access_change
                            where subscription_id = ? order by at desc limit 20""").param(subscriptionId)
                    .query((rs, i) -> {
                        UUID employee = rs.getObject("employee_id", UUID.class);
                        return new AccessChange(rs.getObject("id", UUID.class), employee,
                                employees.card(employee).map(EmployeeDirectory.EmployeeCard::name).orElse(null),
                                rs.getString("action"), rs.getString("status"), rs.getString("detail"),
                                rs.getTimestamp("at").toInstant());
                    }).list();
            return jdbc.sql("""
                            select kind, entra_app_id, entra_app_name, scim_url, scim_token is not null as token,
                                   scim_revoke from subscription_access where subscription_id = ?""")
                    .param(subscriptionId)
                    .query((rs, i) -> new SubscriptionAccess(rs.getString("kind"), rs.getString("entra_app_id"),
                            rs.getString("entra_app_name"), rs.getString("scim_url"), rs.getBoolean("token"),
                            rs.getString("scim_revoke"), recent))
                    .optional()
                    .orElse(new SubscriptionAccess("manual", null, null, null, false, "deactivate", recent));
        });
    }

    SubscriptionAccess save(UUID tenantId, UUID subscriptionId, AccessSettings a, boolean subscriptionExists) {
        if (!subscriptionExists) {
            throw CabinetProblem.SUBSCRIPTION_NOT_FOUND.failure();
        }
        tx.executeWithoutResult(s -> {
            session.bind(tenantId);
            if ("manual".equals(a.kind())) {
                jdbc.sql("delete from subscription_access where subscription_id = ?").param(subscriptionId).update();
                return;
            }
            boolean hadToken = jdbc.sql("""
                            select count(*) from subscription_access where subscription_id = ? and scim_token is not null""")
                    .param(subscriptionId).query(Integer.class).single() > 0;
            boolean newToken = a.scimToken() != null && !a.scimToken().isBlank();
            if ("entra".equals(a.kind()) && (a.entraAppId() == null || a.entraAppId().isBlank())) {
                throw CabinetProblem.ACCESS_SETTINGS.failure();
            }
            if ("scim".equals(a.kind()) && (a.scimUrl() == null || (!newToken && !hadToken))) {
                throw CabinetProblem.ACCESS_SETTINGS.failure();
            }
            if (newToken && !sealer.available()) {
                throw CabinetProblem.ENCRYPTION_KEY_MISSING.failure();
            }
            jdbc.sql("""
                    insert into subscription_access (tenant_id, subscription_id, kind, entra_app_id, entra_app_name,
                        scim_url, scim_token, scim_revoke, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, subscription_id) do update set kind = excluded.kind,
                        entra_app_id = excluded.entra_app_id, entra_app_name = excluded.entra_app_name,
                        scim_url = excluded.scim_url,
                        scim_token = coalesce(excluded.scim_token, subscription_access.scim_token),
                        scim_revoke = excluded.scim_revoke, updated_at = excluded.updated_at""")
                    .params(tenantId, subscriptionId, a.kind(), "entra".equals(a.kind()) ? a.entraAppId() : null,
                            "entra".equals(a.kind()) ? a.entraAppName() : null,
                            "scim".equals(a.kind()) ? a.scimUrl() : null,
                            "scim".equals(a.kind()) && newToken ? sealer.seal(aad(tenantId, subscriptionId),
                                    a.scimToken().strip()) : null,
                            a.scimRevoke() == null ? "deactivate" : a.scimRevoke(), Timestamp.from(clock.instant()))
                    .update();
        });
        return get(tenantId, subscriptionId);
    }

    List<EnterpriseApp> apps(UUID tenantId) {
        try {
            return microsoft.apps(tenantId).stream().map(a -> new EnterpriseApp(a.id(), a.name())).toList();
        } catch (MicrosoftApps.Unavailable e) {
            throw CabinetProblem.MICROSOFT_UNAVAILABLE.failure();
        }
    }
}
