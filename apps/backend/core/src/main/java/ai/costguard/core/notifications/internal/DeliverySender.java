package ai.costguard.core.notifications.internal;

import ai.costguard.core.org.UserDirectory;
import ai.costguard.platform.tenant.TenantSession;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends planned deliveries (ADR 0031). Every few seconds it visits each company with deliveries, takes the due ones
 * under a row lock (another instance skips them) and sends them. A failure is retried after 30 s, 1, 2, 4… minutes;
 * after {@code max-attempts} or a failure that waiting cannot fix (bad address, blocked bot) the delivery is
 * failed. A user who blocked the bot is unlinked. Metric: {@code costguard_notifications_deliveries_total}
 * by channel and result.
 */
@Component
class DeliverySender {

    private static final Logger log = LoggerFactory.getLogger(DeliverySender.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final int BATCH = 50;
    private static final int ERROR_LENGTH = 500;

    private final DeliveryRepository deliveries;
    private final UserDirectory users;
    private final Map<String, Channel> channels;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String webUrl;
    private final int maxAttempts;

    DeliverySender(DeliveryRepository deliveries, UserDirectory users, List<Channel> channels, TenantSession session,
            PlatformTransactionManager transactions, Clock clock, MeterRegistry metrics,
            @Value("${costguard.notifications.web-url:http://localhost:3000}") String webUrl,
            @Value("${costguard.notifications.max-attempts:8}") int maxAttempts) {
        this.deliveries = deliveries;
        this.users = users;
        this.channels = channels.stream().collect(Collectors.toMap(Channel::name, Function.identity()));
        this.session = session;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.metrics = metrics;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${costguard.notifications.send-every:PT5S}",
            initialDelayString = "${costguard.notifications.send-initial-delay:PT10S}")
    void scheduled() {
        try {
            sendDue();
        } catch (RuntimeException e) {
            log.warn("Sending notifications failed, the next round tries again: {}", e.getMessage());
        }
    }

    /** One round over every company; how many deliveries were tried. */
    int sendDue() {
        List<UUID> tenants = tx.execute(status -> deliveries.tenants());
        int tried = 0;
        for (UUID tenant : tenants == null ? List.<UUID>of() : tenants) {
            Integer n = tx.execute(status -> sendFor(tenant));
            tried += n == null ? 0 : n;
        }
        return tried;
    }

    private int sendFor(UUID tenantId) {
        session.bind(tenantId);
        Instant now = clock.instant();
        List<Delivery> due = deliveries.due(now, BATCH);
        if (due.isEmpty()) {
            return 0;
        }
        Map<UUID, UserDirectory.UserCard> byId = users.users().stream()
                .collect(Collectors.toMap(UserDirectory.UserCard::id, Function.identity()));
        for (Delivery d : due) {
            send(tenantId, d, byId.get(d.userId()), now);
        }
        return due.size();
    }

    private void send(UUID tenantId, Delivery d, UserDirectory.UserCard user, Instant now) {
        Channel channel = channels.get(d.channel());
        String address = user == null ? null : address(d.channel(), user);
        if (channel == null || !channel.enabled() || address == null) {
            deliveries.failed(d.id(), channel == null || !channel.enabled() ? "Channel is off" : "No address");
            count(d.channel(), "failed");
            return;
        }
        try {
            channel.send(new Channel.Message(d.text(), d.link() == null ? null : webUrl + d.link(), d.level()),
                    address);
            deliveries.sent(d.id(), now);
            count(d.channel(), "sent");
        } catch (Channel.DeliveryFailure e) {
            String error = e.getMessage().length() > ERROR_LENGTH ? e.getMessage().substring(0, ERROR_LENGTH)
                    : e.getMessage();
            if (e.permanent() || d.attempts() + 1 >= maxAttempts) {
                deliveries.failed(d.id(), error);
                count(d.channel(), "failed");
                if (e.permanent() && "telegram".equals(d.channel())) {
                    deliveries.unlinkTelegram(d.userId());
                }
                log.warn("Notification delivery {} over {} failed for good (tenant {}): {}", d.id(), d.channel(),
                        tenantId, error);
            } else {
                deliveries.retry(d.id(), now.plus(FIRST_RETRY.multipliedBy(1L << Math.min(d.attempts(), 10))), error);
                count(d.channel(), "retry");
            }
        }
    }

    private String address(String channel, UserDirectory.UserCard user) {
        if ("email".equals(channel)) {
            return user.email() == null || user.email().isBlank() ? null : user.email();
        }
        Long chat = deliveries.settings(user.id()).map(Settings::telegramChatId).orElse(null);
        return chat == null ? null : chat.toString();
    }

    private void count(String channel, String result) {
        metrics.counter("costguard.notifications.deliveries", "channel", channel, "result", result).increment();
    }
}
