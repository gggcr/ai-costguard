package ai.costguard.core.shared.inbox;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.Language;
import ai.costguard.platform.tenant.TenantSession;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The company's language for texts of services that do not know it (ADR 0074): the anomaly detector writes Russian,
 * and its signals and findings are put into the company's language as they arrive (DetectorText). Russian for a
 * company without settings, as the rest of the core.
 */
class CompanyLanguages implements Function<UUID, Language> {

    private final TenantSession session;
    private final EmployeeDirectory company;
    private final TransactionTemplate tx;

    CompanyLanguages(TenantSession session, EmployeeDirectory company, PlatformTransactionManager transactions) {
        this.session = session;
        this.company = company;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setReadOnly(true);
    }

    @Override
    public Language apply(UUID tenantId) {
        Language language = tx.execute(status -> {
            session.bind(tenantId);
            return company.company().map(EmployeeDirectory.CompanyCard::language).orElse(Language.RU);
        });
        return language == null ? Language.RU : language;
    }
}
