package ai.costguard.core.privacy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a day: removes what is past its term, then erases the companies whose grace period ended. Running on several
 * instances is safe: deleting what is gone deletes nothing, and a company is locked while it is erased.
 * {@code costguard.privacy.retention.enabled=false} stops the first part only; a company that asked to leave is
 * erased regardless.
 */
@Component
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final PrivacyService service;
    private final PrivacyProperties properties;

    RetentionJob(PrivacyService service, PrivacyProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(cron = "${costguard.privacy.retention.cron:0 30 3 * * *}", zone = "UTC")
    void daily() {
        if (properties.retention().enabled()) {
            log.info("Retention removed or anonymized {} records", service.applyRetention());
        }
        int companies = service.eraseDueCompanies();
        if (companies > 0) {
            log.info("Erased {} companies whose grace period ended", companies);
        }
    }
}
