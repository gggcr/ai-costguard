package ai.costguard.core.journal.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Hashes {

    static final String GENESIS = "0".repeat(64);
    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {
    }

    /** The chain link: sha256 over the previous hash (hex, as text) and the payload. */
    static String link(String previousHash, String payload) {
        return HEX.formatHex(sha256((previousHash + "\n" + payload).getBytes(StandardCharsets.UTF_8)));
    }

    static String pair(String left, String right) {
        byte[] l = HEX.parseHex(left);
        byte[] r = HEX.parseHex(right);
        byte[] both = new byte[l.length + r.length];
        System.arraycopy(l, 0, both, 0, l.length);
        System.arraycopy(r, 0, both, l.length, r.length);
        return HEX.formatHex(sha256(both));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
