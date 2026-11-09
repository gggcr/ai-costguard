package ai.costguard.core.privacy.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The module's own rows. COMPANY_ERASURE stays when the company goes: it is the record that the company was
 * erased, and it holds the tenant id, times and counts only.
 */
@Component
class PrivacyData implements DataHolder {

    private final PrivacyRepository repository;

    PrivacyData(PrivacyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<String> tables() {
        return List.of("erasure", "privacy_tenant");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        report.deleted("privacy.erasure", repository.deleteErasures(tenantId));
        report.deleted("privacy.privacy_tenant", repository.forgetTenant(tenantId));
    }
}
