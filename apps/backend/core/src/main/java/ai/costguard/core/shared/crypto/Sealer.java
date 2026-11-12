package ai.costguard.core.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A secret kept in the database, sealed with AES-256-GCM under {@code costguard.connections.encryption-key}; the
 * associated data (a tenant and what the secret is for) makes a ciphertext copied to another row useless. Layout:
 * version byte 1, 12-byte nonce, ciphertext with the tag.
 */
@Component
public class Sealer {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public Sealer(@Value("${costguard.connections.encryption-key:}") String key) {
        byte[] raw = key.isBlank() ? new byte[0] : Base64.getDecoder().decode(key.trim());
        this.key = raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
    }

    public boolean available() {
        return key != null;
    }

    public byte[] seal(String aad, String secret) {
        if (key == null) {
            throw new IllegalStateException("No encryption key");
        }
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE + sealed.length).put(VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a secret", e);
        }
    }

    public String open(String aad, byte[] box) {
        if (key == null) {
            throw new IllegalStateException("No encryption key");
        }
        if (box.length < 1 + NONCE || box[0] != VERSION) {
            throw new IllegalStateException("Unknown format of a sealed secret");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, box, 1, NONCE));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(box, 1 + NONCE, box.length - 1 - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt a sealed secret", e);
        }
    }
}
