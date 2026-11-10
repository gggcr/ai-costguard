package ai.costguard.core.notifications.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads what people write to the bot (ADR 0031). Polling, not a webhook: the core needs no public address. One
 * instance at a time polls, under a transaction-scoped advisory lock; the offset of the last update is in
 * telegram_state, so whichever instance polls next goes on from there.
 *
 * <p>"/start &lt;code&gt;" from a link of the settings page links that chat to the user. Anything else gets a hint.
 */
@Component
class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    static final String LINKED = "Готово: уведомления CostGuard будут приходить в этот чат. "
            + "Уровни и отключение — в CostGuard, раздел «Уведомления».";
    static final String EXPIRED = "Ссылка устарела или уже использована. Получите новую в CostGuard: "
            + "«Уведомления» → «Подключить Telegram».";
    static final String HELP = "Это бот уведомлений CostGuard. Чтобы получать уведомления, нажмите "
            + "«Подключить Telegram» в разделе «Уведомления» CostGuard и откройте ссылку.";

    private final TelegramApi api;
    private final DeliveryRepository deliveries;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final Clock clock;

    TelegramBot(TelegramApi api, DeliveryRepository deliveries, TenantSession session,
            PlatformTransactionManager transactions, Clock clock) {
        this.api = api;
        this.deliveries = deliveries;
        this.session = session;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${costguard.notifications.telegram.poll-every:PT3S}",
            initialDelayString = "${costguard.notifications.telegram.poll-initial-delay:PT15S}")
    void scheduled() {
        if (!api.enabled()) {
            return;
        }
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("Polling Telegram failed, the next round tries again: {}", e.getMessage());
        }
    }

    /** One poll; how many updates were handled (0 when another instance holds the lock). */
    int poll() {
        Integer handled = tx.execute(status -> {
            if (!deliveries.lockTelegram()) {
                return 0;
            }
            long offset = deliveries.offset();
            List<TelegramApi.Update> updates = api.updates(offset);
            Instant now = clock.instant();
            for (TelegramApi.Update u : updates) {
                handle(u, now);
                offset = Math.max(offset, u.id() + 1);
            }
            deliveries.offset(offset);
            deliveries.forgetExpiredLinks(now);
            return updates.size();
        });
        return handled == null ? 0 : handled;
    }

    private void handle(TelegramApi.Update u, Instant now) {
        String text = u.text().trim();
        String reply;
        if (text.startsWith("/start ")) {
            String code = text.substring("/start ".length()).trim();
            Optional<DeliveryRepository.LinkTarget> target = deliveries.takeLink(ChannelSettings.hash(code));
            if (target.isPresent() && target.get().expiresAt().isAfter(now)) {
                session.bind(target.get().tenantId());
                deliveries.linkTelegram(target.get().tenantId(), target.get().userId(), u.chatId(), now);
                reply = LINKED;
            } else {
                reply = EXPIRED;
            }
        } else {
            reply = HELP;
        }
        try {
            api.send(u.chatId(), reply);
        } catch (TelegramApi.Failure e) {
            log.warn("Telegram reply failed: {}", e.getMessage());
        }
    }
}
