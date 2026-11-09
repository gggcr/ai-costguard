package ai.costguard.core.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * What each kind of data is kept for, from {@code costguard.privacy.retention} (ADR 0021), and the moment the job
 * runs. The terms are the defaults agreed for the lawyer to confirm: see docs/privacy/data-inventory.md.
 */
public record Retention(Instant now, Period dismissedEmployees, Period activity, Period notifications,
        Period handoverActs, Period importFiles, Period actionLog) {

    public LocalDate today() {
        return now.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** The first moment that is still kept for a term. */
    public Instant since(Period term) {
        return now.atOffset(ZoneOffset.UTC).minus(term).toInstant();
    }

    public LocalDate sinceDay(Period term) {
        return today().minus(term);
    }
}
