package ai.costguard.core.privacy;

import java.util.List;
import java.util.UUID;

/** Tombstones for the outbox to send after the company's rows are gone (ADR 0021). */
public record CompactedRecordsErased(UUID tenantId, List<Key> keys) {

    public record Key(String topic, String key) {
    }

    public CompactedRecordsErased {
        keys = List.copyOf(keys);
    }
}
