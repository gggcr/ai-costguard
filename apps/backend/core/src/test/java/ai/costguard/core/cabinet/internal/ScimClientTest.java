package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Finding of 2026-11-13: the vendor's SCIM address must not lead the core into its own network. */
class ScimClientTest {

    private final ScimClient scim = new ScimClient(false);

    @Test
    void addressesInsideTheNetworkAreRefused() {
        for (String base : new String[] {"https://127.0.0.1/scim/v2", "https://localhost/scim/v2",
                "https://10.1.2.3/scim", "https://172.16.0.9/scim", "https://192.168.1.1/scim",
                "https://169.254.169.254/latest", "https://100.64.0.1/scim", "https://0.0.0.0/scim",
                "https://[::1]/scim", "https://[fd00::1]/scim", "https://[fe80::1]/scim", "not a url at all"}) {
            assertThat(scim.internal(base, false)).as(base).isTrue();
        }
    }

    @Test
    void aVendorOnTheInternetIsFine() {
        assertThat(scim.internal("https://8.8.8.8/scim/v2", false)).isFalse();
        assertThat(scim.internal("https://[2001:4860:4860::8888]/scim/v2", false)).isFalse();
        // a host that does not resolve: saved, but never called
        assertThat(scim.internal("https://scim.invalid/v2", false)).isFalse();
        assertThat(scim.internal("https://scim.invalid/v2", true)).isTrue();
    }

    @Test
    void theCoreDoesNotCallAnInternalAddress() {
        assertThatThrownBy(() -> scim.grant("https://127.0.0.1:9/scim/v2", "t", "a@b.example", "A", "B"))
                .isInstanceOf(ScimClient.Failed.class).hasMessageContaining("not a vendor");
        assertThatThrownBy(() -> scim.revoke("https://10.0.0.1/scim/v2", "t", "a@b.example", false))
                .isInstanceOf(ScimClient.Failed.class);
    }

    @Test
    void testsMayAllowTheirLocalServer() {
        assertThat(new ScimClient(true).internal("https://127.0.0.1/scim", true)).isFalse();
    }
}
