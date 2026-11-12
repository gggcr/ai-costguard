package ai.costguard.core.approvals.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canonical forms of ADR 0009, pinned against hashlib. v2 of the approver hash (finding H2 of 2026-11-12) is also
 * computed here from the documented layout, independently of the code. All values are fictitious.
 */
class NotaryHashesTest {

    private static final UUID TENANT = UUID.fromString("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a");
    private static final UUID OPERATION = UUID.fromString("5e0c7a1b-2d3f-4a5b-8c9d-0e1f2a3b4c5d");
    private static final UUID AGENT = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID APPROVAL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static String sha256(String... lines) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void operationHashMatchesTheFormOfTheAdr() {
        assertThat(NotaryHashes.operation(TENANT, OPERATION, AGENT, new BigDecimal("1500.5"), APPROVAL))
                .isEqualTo("46624b387743b3af1d5d65138f87e702afda8a45d9d0daad9ba5c083d23d0286");
    }

    /** The v1 value pinned before V49: the hashes already on the chain keep this form. */
    @Test
    void approverHashMatchesTheFormOfTheAdr() {
        assertThat(NotaryHashes.approverV1(TENANT, APPROVAL, "fin@romashka.example"))
                .isEqualTo("249f1fa909f211f7f40041d42c7fe0b37ca7de3c28bd546ea726ad847fb6bdd2");
    }

    @Test
    void theSamePersonGetsAnotherHashOnEveryApproval() {
        assertThat(NotaryHashes.approverV1(TENANT, UUID.randomUUID(), "fin@romashka.example"))
                .isNotEqualTo(NotaryHashes.approverV1(TENANT, APPROVAL, "fin@romashka.example"));
        assertThat(NotaryHashes.approverV2(TENANT, UUID.randomUUID(), ACCOUNT))
                .isNotEqualTo(NotaryHashes.approverV2(TENANT, APPROVAL, ACCOUNT));
    }

    @Test
    void v1HashesOnTheChainStayCheckable() {
        String old = "249f1fa909f211f7f40041d42c7fe0b37ca7de3c28bd546ea726ad847fb6bdd2";
        assertThat(NotaryHashes.approverMatches(1, TENANT, APPROVAL, "fin@romashka.example", null, old)).isTrue();
        assertThat(NotaryHashes.approverMatches(1, TENANT, APPROVAL, "other@romashka.example", null, old)).isFalse();
        assertThat(NotaryHashes.approverMatches(2, TENANT, APPROVAL, "fin@romashka.example", ACCOUNT, old))
                .as("a v1 hash is not a v2 one").isFalse();
    }

    @Test
    void v2NamesTheAccountAndIsCheckable() throws Exception {
        String now = NotaryHashes.approverV2(TENANT, APPROVAL, ACCOUNT);
        assertThat(now).isEqualTo(sha256("costguard:approver:v2", TENANT.toString(), APPROVAL.toString(),
                ACCOUNT.toString())).matches("[0-9a-f]{64}");
        assertThat(NotaryHashes.approverMatches(2, TENANT, APPROVAL, null, ACCOUNT, now)).isTrue();
        assertThat(NotaryHashes.approverMatches(2, TENANT, APPROVAL, null, UUID.randomUUID(), now))
                .as("another account").isFalse();
        assertThat(NotaryHashes.approverMatches(2, UUID.randomUUID(), APPROVAL, null, ACCOUNT, now))
                .as("another company").isFalse();
        assertThat(NotaryHashes.approverMatches(2, TENANT, UUID.randomUUID(), null, ACCOUNT, now))
                .as("another approval").isFalse();
        assertThat(NotaryHashes.approverMatches(2, TENANT, APPROVAL, null, null, now)).isFalse();
        assertThat(NotaryHashes.approverMatches(3, TENANT, APPROVAL, null, ACCOUNT, now)).isFalse();
        assertThat(NotaryHashes.approverMatches(2, TENANT, APPROVAL, null, ACCOUNT, null)).isFalse();
    }

    @Test
    void theFormatsAreKeptApartByTheirFirstLine() {
        String v2 = NotaryHashes.approverV2(TENANT, APPROVAL, ACCOUNT);
        // a v1 "decided by" that is the account id does not give the v2 hash
        assertThat(NotaryHashes.approverV1(TENANT, APPROVAL, ACCOUNT.toString())).isNotEqualTo(v2);
        // nor does an operation hash with the same ids
        assertThat(NotaryHashes.operation(TENANT, APPROVAL, ACCOUNT, BigDecimal.ONE, APPROVAL)).isNotEqualTo(v2);
    }
}
