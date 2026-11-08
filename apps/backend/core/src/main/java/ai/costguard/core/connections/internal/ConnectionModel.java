package ai.costguard.core.connections.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Where a connection stands:
 * <ul>
 *   <li>{@code pending}: connected, the agent has not pulled it yet;</li>
 *   <li>{@code connected}: the last pull succeeded;</li>
 *   <li>{@code failing}: the last pull failed, see {@code lastError};</li>
 *   <li>{@code sync_requested}: a person asked to sync and the agent has not pulled since;</li>
 *   <li>{@code disconnected}: the agent no longer pulls it.</li>
 * </ul>
 */
enum ConnectionStatus { pending, connected, failing, sync_requested, disconnected }

/**
 * A connected system. {@code managedInCore} is false for one the agent reported that is configured only in the
 * agent's file: its settings are not known here. {@code recordsLastSync} counts what the last successful pull
 * published, {@code recordsTotal} every pull.
 */
record Connection(UUID id, String type, String name, ConnectionStatus status, boolean enabled, boolean managedInCore,
        Map<String, String> settings, Instant lastSyncAt, Integer recordsLastSync, long recordsTotal,
        String lastError, Instant lastErrorAt, Instant syncRequestedAt, Instant createdAt) {
}

record ConnectionDetails(
        @NotBlank String type,
        @NotBlank @Size(max = 120) String name,
        @NotNull Map<String, String> settings) {
}

record ConnectionRun(UUID id, Instant startedAt, Instant finishedAt, String status, int records, String error) {
}
