package ai.costguard.core.policies;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Every enabled rule of a tenant at one version. The topic is compacted, so each snapshot is complete. */
public record PolicySnapshotPublished(UUID tenantId, long version, List<Rule> rules, Instant publishedAt) {

    public record Rule(UUID id, String name, String expression, String effect) {
    }
}
