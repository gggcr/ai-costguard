package ai.costguard.core.org.internal;

import ai.costguard.core.org.MicrosoftSignIns;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class GraphSignIns implements MicrosoftSignIns {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final CompanyLoginService logins;
    private final MicrosoftDirectory microsoft;
    private final Clock clock;

    GraphSignIns(JdbcClient jdbc, TenantSession session, TransactionTemplate transactions,
            CompanyLoginService logins, MicrosoftDirectory microsoft, Clock clock) {
        this.jdbc = jdbc;
        this.session = session;
        this.transactions = transactions;
        this.logins = logins;
        this.microsoft = microsoft;
        this.clock = clock;
    }

    @Override
    public List<UUID> tenants() {
        // login_domain has no RLS: the list of companies with a connected Microsoft app
        return transactions.execute(tx -> jdbc.sql("select distinct tenant_id from login_domain")
                .query(UUID.class).list());
    }

    @Override
    public List<SignIn> recent(UUID tenantId, int days) {
        Optional<MicrosoftDirectory.App> app = transactions.execute(tx -> {
            session.bind(tenantId);
            return logins.app(tenantId);
        });
        if (app.isEmpty()) {
            return List.of();
        }
        List<MicrosoftDirectory.SignIn> log;
        try {
            log = microsoft.signIns(app.get(), clock.instant().minus(Duration.ofDays(days)));
        } catch (MicrosoftDirectory.Unavailable e) {
            throw new Unavailable(e.getMessage());
        }
        Map<String, SignIn> last = new HashMap<>();
        for (MicrosoftDirectory.SignIn s : log) {
            LocalDate day = s.at().atZone(MOSCOW).toLocalDate();
            last.merge(s.email() + "\n" + s.app(), new SignIn(s.email(), s.app(), day),
                    (a, b) -> a.day().isAfter(b.day()) ? a : b);
        }
        return new ArrayList<>(last.values());
    }
}
