package ai.costguard.core.notifications.internal;

import org.springframework.stereotype.Component;

/** Telegram: the bot writes to the chat the user linked (ADR 0031). */
@Component
class TelegramChannel implements Channel {

    private final TelegramApi api;

    TelegramChannel(TelegramApi api) {
        this.api = api;
    }

    static String icon(Level level) {
        return switch (level) {
            case critical -> "🔴 ";
            case warning -> "🟠 ";
            case good -> "🟢 ";
            case info -> "ℹ️ ";
        };
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public boolean enabled() {
        return api.enabled();
    }

    @Override
    public void send(Message message, String address) {
        String text = icon(message.level()) + message.text() + (message.url() == null ? "" : "\n" + message.url());
        try {
            api.send(Long.parseLong(address), text);
        } catch (NumberFormatException e) {
            throw new DeliveryFailure("Bad chat id", true);
        } catch (TelegramApi.Failure e) {
            // 403: the user blocked the bot; 400: the chat is gone. Neither gets better by waiting.
            throw new DeliveryFailure(e.getMessage(), e.status() == 403 || e.status() == 400);
        }
    }
}
