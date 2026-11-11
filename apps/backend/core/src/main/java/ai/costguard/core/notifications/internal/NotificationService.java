package ai.costguard.core.notifications.internal;

import ai.costguard.core.agents.AgentOwners;
import ai.costguard.core.org.UserDirectory;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class NotificationService {

    private final NotificationRepository notifications;
    private final DeliveryRepository deliveries;
    private final UserDirectory users;
    private final AgentOwners agentOwners;
    private final List<Channel> channels;
    private final NotificationStream stream;
    private final TenantSession session;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, DeliveryRepository deliveries, UserDirectory users,
            AgentOwners agentOwners, List<Channel> channels, NotificationStream stream, TenantSession session,
            Clock clock) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.users = users;
        this.agentOwners = agentOwners;
        this.channels = channels;
        this.stream = stream;
        this.session = session;
        this.clock = clock;
    }

    /** Into the company's feed only. */
    @Transactional
    void notify(UUID tenantId, String text, String link, Level level) {
        notify(tenantId, text, link, level, null);
    }

    /**
     * Into the feed, and to the audience over the channels each of them chose (ADR 0031). Stored first, deliveries
     * planned in the same transaction; pushed to open streams only after commit, so nobody sees a notification that
     * was rolled back.
     */
    @Transactional
    void notify(UUID tenantId, String text, String link, Level level, Audience audience) {
        session.bind(tenantId);
        Notification n = new Notification(UUID.randomUUID(), clock.instant(), text, link, level, false);
        notifications.insert(tenantId, n);
        if (audience != null) {
            plan(tenantId, n, audience);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stream.send(tenantId, n);
            }
        });
    }

    /** One employee's notification (ADR 0052): their cabinet feed and their channels, not the company's feed. */
    @Transactional
    void notifyPersonal(UUID tenantId, UUID employeeId, String text, String link, Level level) {
        session.bind(tenantId);
        Notification n = new Notification(UUID.randomUUID(), clock.instant(), text, link, level, false);
        notifications.insertPersonal(tenantId, employeeId, n);
        plan(tenantId, n, new Audience(java.util.Set.of(), employeeId, null));
    }

    @Transactional(readOnly = true)
    NotificationPage personalPage(UUID tenantId, UUID employeeId) {
        session.bind(tenantId);
        return new NotificationPage(notifications.personal(employeeId, 200), notifications.unreadPersonal(employeeId));
    }

    @Transactional
    void markPersonalRead(UUID tenantId, UUID employeeId, UUID id) {
        session.bind(tenantId);
        notifications.markReadPersonal(employeeId, id);
    }

    @Transactional
    void markAllPersonalRead(UUID tenantId, UUID employeeId) {
        session.bind(tenantId);
        notifications.markAllReadPersonal(employeeId);
    }

    private void plan(UUID tenantId, Notification n, Audience audience) {
        UUID owner = audience.agentId() == null ? null : agentOwners.ownerOf(audience.agentId()).orElse(null);
        for (UserDirectory.UserCard user : users.users()) {
            boolean addressed = audience.roles().contains(user.role())
                    || (user.employeeId() != null && (user.employeeId().equals(audience.employeeId())
                            || user.employeeId().equals(owner)));
            if (!addressed) {
                continue;
            }
            Settings settings = deliveries.settings(user.id()).orElse(Settings.defaults(user.id()));
            for (Channel channel : channels) {
                if (channel.enabled() && wants(settings, channel.name(), n.level(), user)) {
                    deliveries.insert(tenantId, n.id(), user.id(), channel.name(), n.at());
                }
            }
        }
    }

    private static boolean wants(Settings s, String channel, Level level, UserDirectory.UserCard user) {
        return switch (channel) {
            case "email" -> user.email() != null && !user.email().isBlank() && s.emailLevel().allows(level);
            case "telegram" -> s.telegramChatId() != null && s.telegramLevel().allows(level);
            default -> false;
        };
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
