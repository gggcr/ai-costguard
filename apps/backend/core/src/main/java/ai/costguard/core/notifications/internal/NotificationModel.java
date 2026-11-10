package ai.costguard.core.notifications.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum Level { info, warning, critical, good }

record Notification(UUID id, Instant at, String text, String link, Level level, boolean read) {
}

record NotificationPage(List<Notification> items, int unread) {
}

/** From which level a channel gets notifications; {@code off} means never. good counts as info. */
enum Threshold {
    off, info, warning, critical;

    boolean allows(Level level) {
        int rank = switch (level) {
            case critical -> 3;
            case warning -> 2;
            case info, good -> 1;
        };
        return this != off && rank >= ordinal();
    }
}

/** A user's channels. Without a saved row: e-mail for critical ones, Telegram from warning once linked. */
record Settings(UUID userId, Threshold emailLevel, Threshold telegramLevel, Long telegramChatId,
        Instant telegramLinkedAt) {

    static Settings defaults(UUID userId) {
        return new Settings(userId, Threshold.critical, Threshold.warning, null, null);
    }
}

/** The settings card of the web client. {@code *Available}: the channel is configured on the server. */
record SettingsView(String email, Threshold emailLevel, boolean emailAvailable, Threshold telegramLevel,
        boolean telegramLinked, Instant telegramLinkedAt, boolean telegramAvailable) {
}

record SettingsChange(@NotNull Threshold emailLevel, @NotNull Threshold telegramLevel) {
}

/** Open this link in Telegram and press Start; it works once, until {@code expiresAt}. */
record TelegramLink(String url, Instant expiresAt) {
}

record Delivery(UUID id, UUID notificationId, UUID userId, String channel, int attempts, String text, String link,
        Level level) {
}
