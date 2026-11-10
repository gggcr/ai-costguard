package ai.costguard.core.org.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registration rate limit (ADR 0036): attempts per client address and per e-mail in a sliding window, in
 * PostgreSQL so that every core instance counts the same. Every attempt counts, refused ones too, so hammering does
 * not reset the window. Only SHA-256 hashes are stored, and only for the window.
 */
@Component
class RegistrationAttempts {

    private final RegistrationRepository repository;
    private final RegistrationProperties.RateLimit limit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    RegistrationAttempts(RegistrationRepository repository, RegistrationProperties properties,
            TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.limit = properties.rateLimit();
        this.transactions = transactions;
        this.clock = clock;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records the attempt and says whether it is within the limit. Commits on its own, whatever follows. */
    boolean allow(String clientAddress, String email) {
        Instant now = clock.instant();
        Instant since = now.minus(limit.window());
        String ip = hash(clientAddress == null ? "" : clientAddress);
        String mail = hash(email);
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            repository.attempt("ip", ip, now);
            repository.attempt("email", mail, now);
            return repository.attempts("ip", ip, since) <= limit.perIp()
                    && repository.attempts("email", mail, since) <= limit.perEmail();
        }));
    }

    /** Attempts older than the window count for nothing and are deleted. */
    int forgetOld() {
        Instant before = clock.instant().minus(limit.window());
        Integer n = transactions.execute(tx -> repository.forgetAttempts(before));
        return n == null ? 0 : n;
    }
}
