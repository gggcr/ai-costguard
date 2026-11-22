package ai.costguard.core.org.internal;

import ai.costguard.core.org.MicrosoftSignIns;
import ai.costguard.core.shared.names.TimeZones;
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

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final CompanyLoginService logins;
    private final MicrosoftDirectory microsoft;
    private final Clock clock;
    private final OrgRepository org;

    GraphSignIns(JdbcClient jdbc, TenantSession session, TransactionTemplate transactions,
            CompanyLoginService logins, MicrosoftDirectory microsoft, Clock clock, OrgRepository org) {
        this.jdbc = jdbc;
        this.session = session;
        this.transactions = transactions;
        this.logins = logins;
        this.microsoft = microsoft;
        this.clock = clock;
        this.org = org;
    }

    @Override
    public List<UUID> tenants() {
        // login_domain has no RLS: the list of companies with a connected Microsoft app
        return transactions.execute(tx -> jdbc.sql("select distinct tenant_id from login_domain")
                .query(UUID.class).list());
    }

    @Override
    public List<SignIn> recent(UUID tenantId, int days) {
        ZoneId[] zone = {TimeZones.LEGACY};
        Optional<MicrosoftDirectory.App> app = transactions.execute(tx -> {
            session.bind(tenantId);
            org.companyCard().ifPresent(c -> zone[0] = c.zone()); // the day of a sign-in is the company's (ADR 0073)
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
            LocalDate day = s.at().atZone(zone[0]).toLocalDate();
            last.merge(s.email() + "\n" + s.app(), new SignIn(s.email(), s.app(), day),
                    (a, b) -> a.day().isAfter(b.day()) ? a : b);
        }
        return new ArrayList<>(last.values());
    }
}
