package ai.costguard.core.org.internal;

import ai.costguard.core.org.MicrosoftApps;
import ai.costguard.platform.tenant.TenantSession;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class GraphApps implements MicrosoftApps {

    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final CompanyLoginService logins;
    private final MicrosoftDirectory microsoft;

    GraphApps(TenantSession session, TransactionTemplate transactions, CompanyLoginService logins,
            MicrosoftDirectory microsoft) {
        this.session = session;
        this.transactions = transactions;
        this.logins = logins;
        this.microsoft = microsoft;
    }

    private MicrosoftDirectory.App app(UUID tenantId) {
        return transactions.execute(tx -> {
            session.bind(tenantId);
            return logins.app(tenantId);
        }).orElseThrow(() -> new Unavailable("Microsoft 365 is not connected with a kept client secret"));
    }

    @Override
    public List<App> apps(UUID tenantId) {
        try {
            return microsoft.enterpriseApps(app(tenantId)).stream().map(a -> new App(a.id(), a.name())).toList();
        } catch (MicrosoftDirectory.Unavailable e) {
            throw new Unavailable(e.getMessage());
        }
    }

    @Override
    public void assign(UUID tenantId, String appId, String email) {
        try {
            microsoft.assign(app(tenantId), appId, email);
        } catch (MicrosoftDirectory.Unavailable e) {
            throw new Unavailable(e.getMessage());
        }
    }

    @Override
    public void unassign(UUID tenantId, String appId, String email) {
        try {
            microsoft.unassign(app(tenantId), appId, email);
        } catch (MicrosoftDirectory.Unavailable e) {
            throw new Unavailable(e.getMessage());
        }
    }
}
