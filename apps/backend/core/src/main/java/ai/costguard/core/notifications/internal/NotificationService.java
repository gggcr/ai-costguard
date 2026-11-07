package ai.costguard.core.notifications.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationStream stream;
    private final TenantSession session;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, NotificationStream stream, TenantSession session,
            Clock clock) {
        this.notifications = notifications;
        this.stream = stream;
        this.session = session;
        this.clock = clock;
    }

    /** Stored first; pushed to open streams only after commit, so nobody sees a notification that was rolled back. */
    @Transactional
    void notify(UUID tenantId, String text, String link, Level level) {
        session.bind(tenantId);
        Notification n = new Notification(UUID.randomUUID(), clock.instant(), text, link, level, false);
        notifications.insert(tenantId, n);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stream.send(tenantId, n);
            }
        });
    }

    @Transactional(readOnly = true)
    NotificationPage page(UUID tenantId) {
        session.bind(tenantId);
        return new NotificationPage(notifications.latest(200), notifications.unread());
    }

    @Transactional
    void markRead(UUID tenantId, UUID id) {
        session.bind(tenantId);
        notifications.markRead(id);
    }

    @Transactional
    void markAllRead(UUID tenantId) {
        session.bind(tenantId);
        notifications.markAllRead();
    }

    @Transactional
    void clear(UUID tenantId) {
        session.bind(tenantId);
        notifications.clear();
    }
}
