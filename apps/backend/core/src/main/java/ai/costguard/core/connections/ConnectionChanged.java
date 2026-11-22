package ai.costguard.core.connections;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the connector agent must know about a connection, in full, because the topic it feeds is compacted.
 * {@code enabled} is false once disconnected; {@code syncRequestedAt} is set when a person asked to sync now.
 * {@code currency} is the company's, for an amount the system names no currency for (ADR 0072); null until the
 * company is saved.
 */
public record ConnectionChanged(UUID tenantId, UUID connectionId, String type, String name, boolean enabled,
        Map<String, String> settings, Instant syncRequestedAt, Instant updatedAt, String currency) {
}
