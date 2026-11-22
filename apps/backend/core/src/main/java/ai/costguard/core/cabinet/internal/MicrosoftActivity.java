package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.MicrosoftSignIns;
import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Простой лицензий по журналу входов Microsoft (ADR 0063): every morning the sign-ins of the last days mark the
 * licenses of the programs people signed in to as used — Microsoft 365 itself and every app with single sign-on
 * through Entra ID. A license nobody signs in to stays idle and goes back to the pool (ADR 0055).
 */
@Component
class MicrosoftActivity {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftActivity.class);

    private final MicrosoftSignIns signIns;
    private final LicensePool pool;
    private final ProgramMatcher matcher;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final int idleDays;

    MicrosoftActivity(MicrosoftSignIns signIns, LicensePool pool, ProgramMatcher matcher, TenantSession session,
            TransactionTemplate tx, @Value("${costguard.audit.unused-days:30}") int idleDays) {
        this.signIns = signIns;
        this.pool = pool;
        this.matcher = matcher;
        this.session = session;
        this.tx = tx;
        this.idleDays = idleDays;
    }

    @Scheduled(cron = "${costguard.microsoft.activity-cron:0 30 3 * * *}", zone = "UTC")
    void daily() {
        for (UUID tenant : signIns.tenants()) {
            try {
                MicrosoftActivityResult a = load(tenant, 3);
                log.info("Microsoft sign-ins of tenant {}: {} licenses marked used", tenant, a.licensesUpdated());
            } catch (RuntimeException e) {
                log.warn("Microsoft sign-ins of tenant {} not loaded: {}", tenant, e.getMessage());
            }
        }
    }

    MicrosoftActivityResult load(UUID tenantId, int days) {
        List<MicrosoftSignIns.SignIn> recent = signIns.recent(tenantId, days);
        return tx.execute(s -> {
            session.bind(tenantId);
            Map<String, Optional<UUID>> byApp = new HashMap<>();
            TreeSet<String> unmatched = new TreeSet<>();
            int updated = 0;
            for (MicrosoftSignIns.SignIn in : recent) {
                Optional<UUID> subscription = byApp.computeIfAbsent(in.app(), app -> matcher
                        .match(pool, idleDays, app, null, false).map(m -> m.offer().subscriptionId()));
                if (subscription.isEmpty()) {
                    unmatched.add(in.app());
                    continue;
                }
                if (pool.used(subscription.get(), in.email(), in.day())) {
                    updated++;
                }
            }
            return new MicrosoftActivityResult(recent.size(), updated, List.copyOf(unmatched));
        });
    }
}
