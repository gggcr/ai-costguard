package ai.costguard.core.notifications.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum Level { info, warning, critical, good }

record Notification(UUID id, Instant at, String text, String link, Level level, boolean read) {
}

record NotificationPage(List<Notification> items, int unread) {
}
