package ai.costguard.core.approvals.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The canonical forms of ADR 0009, pinned against hashlib. */
class NotaryHashesTest {

    private static final UUID TENANT = UUID.fromString("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a");
    private static final UUID OPERATION = UUID.fromString("5e0c7a1b-2d3f-4a5b-8c9d-0e1f2a3b4c5d");
    private static final UUID AGENT = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID APPROVAL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void operationHashMatchesTheFormOfTheAdr() {
        assertThat(NotaryHashes.operation(TENANT, OPERATION, AGENT, new BigDecimal("1500.5"), APPROVAL))
                .isEqualTo("46624b387743b3af1d5d65138f87e702afda8a45d9d0daad9ba5c083d23d0286");
    }

    @Test
    void approverHashMatchesTheFormOfTheAdr() {
        assertThat(NotaryHashes.approver(TENANT, APPROVAL, "fin@romashka.example"))
                .isEqualTo("249f1fa909f211f7f40041d42c7fe0b37ca7de3c28bd546ea726ad847fb6bdd2");
    }

    @Test
    void theSamePersonGetsAnotherHashOnEveryApproval() {
        assertThat(NotaryHashes.approver(TENANT, UUID.randomUUID(), "fin@romashka.example"))
                .isNotEqualTo(NotaryHashes.approver(TENANT, APPROVAL, "fin@romashka.example"));
    }
}
