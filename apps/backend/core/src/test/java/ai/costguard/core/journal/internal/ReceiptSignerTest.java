package ai.costguard.core.journal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** A rotated key keeps verifying what it signed (ADR 0028). */
class ReceiptSignerTest {

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void receiptsOfTheOldKeyStillVerifyAfterRotation() throws Exception {
        KeyPair old = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair fresh = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ReceiptSigner before = new ReceiptSigner(b64(old.getPrivate().getEncoded()), b64(old.getPublic().getEncoded()),
                "");
        String signed = before.sign("a".repeat(64));

        ReceiptSigner after = new ReceiptSigner(b64(fresh.getPrivate().getEncoded()),
                b64(fresh.getPublic().getEncoded()), b64(old.getPublic().getEncoded()));

        assertThat(after.keyId()).isNotEqualTo(before.keyId());
        assertThat(after.knows(before.keyId())).isTrue();
        assertThat(after.verify(before.keyId(), "a".repeat(64), signed)).isTrue();
        assertThat(after.verify(after.keyId(), "a".repeat(64), signed)).isFalse();
        assertThat(after.keys()).extracting(SigningKey::keyId).containsExactly(after.keyId(), before.keyId());

        ReceiptSigner unaware = new ReceiptSigner(b64(fresh.getPrivate().getEncoded()),
                b64(fresh.getPublic().getEncoded()), "");
        assertThat(unaware.knows(before.keyId())).isFalse();
        assertThat(unaware.verify(before.keyId(), "a".repeat(64), signed)).isFalse();
    }
}
