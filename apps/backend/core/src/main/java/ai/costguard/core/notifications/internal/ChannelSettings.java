package ai.costguard.core.notifications.internal;

import ai.costguard.core.org.UserDirectory;
import ai.costguard.platform.tenant.TenantSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's own channels: levels, linking and unlinking Telegram (ADR 0031). The user is the one whose e-mail is in
 * the token; a person without a CostGuard account in the company gets {@code user_not_found}.
 */
@Service
class ChannelSettings {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryRepository deliveries;
    private final UserDirectory users;
    private final EmailChannel email;
    private final TelegramApi telegram;
    private final TenantSession session;
    private final Clock clock;
    private final Duration linkTtl;

    ChannelSettings(DeliveryRepository deliveries, UserDirectory users, EmailChannel email, TelegramApi telegram,
            TenantSession session, Clock clock,
            @Value("${costguard.notifications.telegram.link-ttl:PT15M}") Duration linkTtl) {
        this.deliveries = deliveries;
        this.users = users;
        this.email = email;
        this.telegram = telegram;
        this.session = session;
        this.clock = clock;
        this.linkTtl = linkTtl;
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The account of the token's subject (sub); the token's e-mail would let a person reach someone else's. */
    private UserDirectory.UserCard me(String subject) {
        return users.userBySubject(subject).orElseThrow(NotificationProblem.USER_NOT_FOUND::failure);
    }

    private SettingsView view(UserDirectory.UserCard user) {
        Settings s = deliveries.settings(user.id()).orElse(Settings.defaults(user.id()));
        return new SettingsView(user.email(), s.emailLevel(), email.enabled(), s.telegramLevel(),
                s.telegramChatId() != null, s.telegramLinkedAt(), telegram.enabled());
    }

    @Transactional(readOnly = true)
    SettingsView get(UUID tenantId, String subject) {
        session.bind(tenantId);
        return view(me(subject));
    }

    @Transactional
    SettingsView save(UUID tenantId, String subject, SettingsChange change) {
        session.bind(tenantId);
        UserDirectory.UserCard user = me(subject);
        deliveries.saveLevels(tenantId, user.id(), change.emailLevel(), change.telegramLevel());
        return view(user);
    }

    /** A one-time link to the bot; a new one replaces the previous. The code itself is not stored, its hash is. */
    @Transactional
    TelegramLink link(UUID tenantId, String subject) {
        if (!telegram.enabled()) {
            throw NotificationProblem.TELEGRAM_UNAVAILABLE.failure();
        }
        session.bind(tenantId);
        UserDirectory.UserCard user = me(subject);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = clock.instant().plus(linkTtl);
        deliveries.saveLink(hash(code), tenantId, user.id(), expires);
        String bot;
        try {
            bot = telegram.username();
        } catch (TelegramApi.Failure e) {
            throw NotificationProblem.TELEGRAM_UNAVAILABLE.failure();
        }
        return new TelegramLink("https://t.me/" + bot + "?start=" + code, expires);
    }

    @Transactional
    SettingsView unlink(UUID tenantId, String subject) {
        session.bind(tenantId);
        UserDirectory.UserCard user = me(subject);
        deliveries.unlinkTelegram(user.id());
        return view(user);
    }
}
