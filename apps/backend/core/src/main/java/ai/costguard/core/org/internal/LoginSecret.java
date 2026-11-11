package ai.costguard.core.org.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The client secret of a company's Microsoft app, sealed like a connection's secrets (AES-256-GCM, key
 * {@code costguard.connections.encryption-key}, the tenant id as associated data). Layout: version byte 1, 12-byte
 * nonce, ciphertext with the tag.
 */
@Component
class LoginSecret {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    LoginSecret(@Value("${costguard.connections.encryption-key:}") String key) {
        if (key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(key.trim());
        this.key = raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
    }

    boolean available() {
        return key != null;
    }

    private static byte[] aad(UUID tenantId) {
        return (tenantId + ":company_login").getBytes(StandardCharsets.US_ASCII);
    }

    byte[] seal(UUID tenantId, String secret) {
        if (key == null) {
            throw CompanyLoginProblem.ENCRYPTION_KEY_MISSING.failure();
        }
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(tenantId));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE + sealed.length).put(VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the client secret", e);
        }
    }

    String open(UUID tenantId, byte[] box) {
        if (key == null) {
            throw CompanyLoginProblem.ENCRYPTION_KEY_MISSING.failure();
        }
        if (box.length < 1 + NONCE || box[0] != VERSION) {
            throw new IllegalStateException("Unknown format of the client secret");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, box, 1, NONCE));
            cipher.updateAAD(aad(tenantId));
            return new String(cipher.doFinal(box, 1 + NONCE, box.length - 1 - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt the client secret", e);
        }
    }
}
