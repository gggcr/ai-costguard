package ai.costguard.core.privacy;

import java.time.LocalDate;

/** What a store did. {@code store} is module.table or a system, as in docs/privacy/data-inventory.md. */
public interface Report {

    void deleted(String store, long rows);

    void anonymized(String store, long rows);

    /** Kept on purpose: {@code why} is the basis, {@code until} the day retention removes it, or null. */
    void retained(String store, long rows, String why, LocalDate until);

    /** A person must do it: the system cannot, or must not, do it alone. */
    void manual(String store, String what);

    /** A record of a compacted topic to remove with a tombstone after commit. */
    void tombstone(String topic, String key);
}
