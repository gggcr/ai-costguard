package ai.costguard.core.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.core.Ordered;

/**
 * A module's part of retention and erasure (ADR 0021). Every call runs inside a transaction already bound to the
 * tenant, and the module touches only its own tables. What it did goes to the {@link Report}: counts, never what
 * the rows said.
 *
 * <p>Holders run in {@link #getOrder()} order. The org module runs last: it blanks the employee the others still
 * look up by e-mail.
 */
public interface DataHolder extends Ordered {

    /** The tables of the module that hold tenant data. A test checks that together they cover the schema. */
    List<String> tables();

    /**
     * Deletes or anonymizes what the module holds about the person. What must be kept (an accounting document) is
     * reported as retained, with the day {@code retention} removes it. Most modules hold nothing personal.
     */
    default void eraseEmployee(Subject subject, Retention retention, Report report) {
    }

    /**
     * Adds what the module holds about the person to an export for a request of access (ADR 0079). A module that
     * erases something in {@link #eraseEmployee} must export it here; a test checks that both are overridden.
     */
    default void exportEmployee(Subject subject, PersonalDataExport export) {
    }

    /** Deletes or anonymizes what is past its term. */
    default void expire(Retention retention, Report report) {
    }

    /**
     * Deletes every row of the company. Rows that are evidence can be deleted here: the transaction is in purge
     * mode. Records on compacted Kafka topics are named with {@link Report#tombstone}, the outbox sends the
     * tombstones after commit.
     */
    void eraseCompany(UUID tenantId, Report report);

    @Override
    default int getOrder() {
        return 0;
    }
}
