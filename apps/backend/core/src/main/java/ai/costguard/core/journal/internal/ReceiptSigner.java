package ai.costguard.core.journal.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs receipt hashes with Ed25519. The key comes from {@code costguard.journal.private-key} and
 * {@code costguard.journal.public-key} (base64 PKCS#8 and X.509), which Vault fills in production. Without them a
 * key is generated for this run only, and receipts it signs cannot be verified after a restart.
 */
@Component
class ReceiptSigner {

    private static final Logger log = LoggerFactory.getLogger(ReceiptSigner.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    ReceiptSigner(@Value("${costguard.journal.private-key:}") String privateKey,
            @Value("${costguard.journal.public-key:}") String publicKey) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        if (privateKey.isBlank() || publicKey.isBlank()) {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            log.warn("No journal signing key configured: using a key for this run only. Set "
                    + "costguard.journal.private-key and costguard.journal.public-key outside development.");
        } else {
            this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
            this.publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        }
        this.keyId = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(this.publicKey.getEncoded())).substring(0, 16);
    }

    String keyId() {
        return keyId;
    }

    String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    String sign(String hash) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(hash.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean verify(String hash, String signatureBase64) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(hash.getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
