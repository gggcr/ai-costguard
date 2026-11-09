package ai.costguard.core.connections.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts a connection's secrets with AES-256-GCM. The key is {@code costguard.connections.encryption-key}
 * (base64 of 32 bytes, from Vault or Lockbox in production); without it secrets are refused rather than kept under
 * a key that a restart would lose. The tenant and connection ids are the associated data, so a ciphertext copied
 * to another row does not decrypt. Layout: version byte 1, 12-byte nonce, ciphertext with the tag.
 */
@Component
class SecretBox {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte VERSION = 1;
    private static final int NONCE = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SecretBox(@Value("${costguard.connections.encryption-key:}") String key) {
        if (key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("costguard.connections.encryption-key must be base64 of 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    private static byte[] aad(UUID tenantId, UUID connectionId) {
        return (tenantId + ":" + connectionId).getBytes(StandardCharsets.US_ASCII);
    }

    byte[] seal(UUID tenantId, UUID connectionId, Map<String, String> secrets) {
        if (key == null) {
            throw ConnectionProblem.ENCRYPTION_KEY_MISSING.failure();
        }
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(tenantId, connectionId));
            byte[] sealed = cipher.doFinal(JSON.writeValueAsBytes(secrets));
            return ByteBuffer.allocate(1 + NONCE + sealed.length).put(VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not encrypt connection secrets", e);
        }
    }

    Map<String, String> open(UUID tenantId, UUID connectionId, byte[] box) {
        if (box == null) {
            return Map.of();
        }
        if (key == null) {
            throw ConnectionProblem.ENCRYPTION_KEY_MISSING.failure();
        }
        if (box.length < 1 + NONCE || box[0] != VERSION) {
            throw new IllegalStateException("Unknown secret format of connection " + connectionId);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, box, 1, NONCE));
            cipher.updateAAD(aad(tenantId, connectionId));
            byte[] plain = cipher.doFinal(box, 1 + NONCE, box.length - 1 - NONCE);
            return JSON.readValue(plain, new TypeReference<Map<String, String>>() { });
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not decrypt the secrets of connection " + connectionId, e);
        }
    }
}
