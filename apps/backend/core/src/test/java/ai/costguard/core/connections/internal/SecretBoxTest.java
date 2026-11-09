package ai.costguard.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.costguard.platform.errors.ApiException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    final UUID tenant = UUID.randomUUID();
    final UUID connection = UUID.randomUUID();

    @Test
    void sealedSecretsOpenOnlyForTheirRow() {
        SecretBox box = new SecretBox(KEY);
        byte[] sealed = box.seal(tenant, connection, Map.of("OKTA_TOKEN", "t0ken"));

        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("t0ken");
        assertThat(box.open(tenant, connection, sealed)).containsEntry("OKTA_TOKEN", "t0ken");
        assertThat(box.seal(tenant, connection, Map.of("OKTA_TOKEN", "t0ken"))).isNotEqualTo(sealed);
        assertThatThrownBy(() -> box.open(UUID.randomUUID(), connection, sealed))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> box.open(tenant, UUID.randomUUID(), sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyDoesNotOpenThem() {
        byte[] sealed = new SecretBox(KEY).seal(tenant, connection, Map.of("a", "b"));
        SecretBox other = new SecretBox("ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        assertThatThrownBy(() -> other.open(tenant, connection, sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeySecretsAreRefused() {
        SecretBox box = new SecretBox("");
        assertThatThrownBy(() -> box.seal(tenant, connection, Map.of("a", "b"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new SecretBox("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
        assertThat(box.open(tenant, connection, null)).isEmpty();
    }
}
