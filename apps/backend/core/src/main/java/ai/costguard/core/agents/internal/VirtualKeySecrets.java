package ai.costguard.core.agents.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** A key has 256 random bits, so a plain SHA-256 is enough: there is nothing to brute-force, unlike a password. */
final class VirtualKeySecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PREFIX_LENGTH = 14;

    private VirtualKeySecrets() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "cg_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String prefix(String secret) {
        return secret.substring(0, PREFIX_LENGTH);
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
