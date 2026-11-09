package ai.costguard.core.privacy.internal;

import java.time.Duration;
import java.time.Period;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code costguard.privacy} (ADR 0021). The terms are the defaults agreed on 2026-11-09 for the lawyer to confirm;
 * docs/privacy/data-inventory.md lists them per kind of data.
 *
 * @param companyGrace how long an owner can cancel erasing the company before it runs
 * @param kafkaRetention how long Kafka keeps a message (log_retention_hours in deploy/terraform), for the report
 * @param logRetention how long the platform keeps service logs, for the report
 * @param clickhouseTables the ClickHouse tables that hold rows of a company ({@code costguard.clickhouse} connects)
 */
@ConfigurationProperties("costguard.privacy")
record PrivacyProperties(
        @DefaultValue RetentionTerms retention,
        @DefaultValue("P30D") Period companyGrace,
        @DefaultValue("P7D") Period kafkaRetention,
        @DefaultValue("P30D") Period logRetention,
        @DefaultValue Keycloak keycloak,
        @DefaultValue({"cost_records", "agent_operations"}) List<String> clickhouseTables) {

    record RetentionTerms(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 30 3 * * *") String cron,
            @DefaultValue("P1Y") Period dismissedEmployees,
            @DefaultValue("P1Y") Period activity,
            @DefaultValue("P1Y") Period notifications,
            @DefaultValue("P5Y") Period handoverActs,
            @DefaultValue("P5Y") Period importFiles,
            @DefaultValue("P3Y") Period actionLog) {
    }

    /** The admin API client that deletes accounts. Off while {@code clientSecret} is empty. */
    record Keycloak(
            @DefaultValue("http://localhost:8180") String url,
            @DefaultValue("costguard") String realm,
            @DefaultValue("costguard-privacy") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("PT10S") Duration timeout) {

        boolean configured() {
            return clientSecret != null && !clientSecret.isBlank();
        }
    }

}
