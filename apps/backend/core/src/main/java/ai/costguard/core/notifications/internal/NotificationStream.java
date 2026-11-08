package ai.costguard.core.notifications.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open SSE connections per tenant on this instance. With several instances a client sees what its own instance
 * stored; the feed itself is in PostgreSQL, so a reconnect fills any gap.
 */
@Component
class NotificationStream {

    private static final long TIMEOUT = Duration.ofMinutes(30).toMillis();

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter open(UUID tenantId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        Set<SseEmitter> set = emitters.computeIfAbsent(tenantId, t -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    void send(UUID tenantId, Notification notification) {
        for (SseEmitter emitter : emitters.getOrDefault(tenantId, Set.of())) {
            try {
                emitter.send(SseEmitter.event().id(notification.id().toString()).name("notification").data(notification));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
    }

    int connections(UUID tenantId) {
        return emitters.getOrDefault(tenantId, Set.of()).size();
    }
}
