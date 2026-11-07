package ai.costguard.core.findings.internal;

import ai.costguard.core.findings.Audit;
import ai.costguard.core.findings.AuditRules;
import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the rules of every module for every tenant. Tenants come from audit_tenant, which triggers fill when a
 * tenant gets its first subscription, license, contract or asset: under RLS no query can list tenants.
 */
@Component
class AuditEngine implements Audit {

    private static final Logger log = LoggerFactory.getLogger(AuditEngine.class);

    private final List<AuditRules> providers;
    private final FindingsIntake intake;
    private final FindingRepository findings;
    private final TenantSession session;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    AuditEngine(List<AuditRules> providers, FindingsIntake intake, FindingRepository findings, TenantSession session,
            JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.providers = providers;
        this.intake = intake;
        this.findings = findings;
        this.session = session;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${costguard.audit.delay:PT1H}", initialDelayString = "${costguard.audit.delay:PT1H}")
    void everyTenant() {
        List<UUID> tenants = jdbc.sql("select tenant_id from audit_tenant").query(UUID.class).list();
        int reported = 0;
        for (UUID tenant : tenants) {
            try {
                // run() called from here is not proxied, so @Transactional would not apply and the tenant binding,
                // which lives only as long as a transaction, would be gone before the first query.
                Integer n = tx.execute(status -> run(tenant));
                reported += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("Audit of tenant {} failed", tenant, e);
            }
        }
        log.info("Audit of {} tenants reported {} findings", tenants.size(), reported);
    }

    @Override
    @Transactional
    public int run(UUID tenantId) {
        session.bind(tenantId);
        LocalDate today = LocalDate.now(clock);
        int reported = 0;
        for (AuditRules provider : providers) {
            Set<String> seen = new HashSet<>();
            for (AuditRules.Detection d : provider.detect(tenantId, today)) {
                intake.detected(tenantId, d.fingerprint(), d.rule(), d.title(), d.description(), d.expectedSavings(),
                        d.subscriptionId(), null);
                seen.add(d.fingerprint());
                reported++;
            }
            for (String rule : provider.rules()) {
                for (String fingerprint : findings.openFingerprints(rule)) {
                    if (!seen.contains(fingerprint)) {
                        intake.resolved(tenantId, fingerprint);
                    }
                }
            }
        }
        return reported;
    }
}
