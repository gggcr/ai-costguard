package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.events.Finding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindingInboxTest {

    record Call(UUID tenant, String fingerprint, String rule, BigDecimal savings, UUID subscription, UUID agent,
            String title) {
    }

    final List<Call> calls = new ArrayList<>();

    final FindingsIntake intake = new FindingsIntake() {
        @Override
        public UUID detected(UUID tenantId, String fingerprint, String rule, String title, String description,
                BigDecimal savings, UUID subscriptionId, UUID agentId) {
            calls.add(new Call(tenantId, fingerprint, rule, savings, subscriptionId, agentId, title));
            return UUID.randomUUID();
        }

        @Override
        public void resolved(UUID tenantId, String fingerprint) {
        }

        @Override
        public void settled(UUID tenantId, String fingerprint, String what) {
        }

        @Override
        public void invoiced(UUID tenantId, ai.costguard.core.findings.InvoiceEvidence invoice) {
        }
    };

    Finding finding(List<String> entities) {
        return Finding.newBuilder().setFingerprint("anomaly.spike:x").setTenantId(UUID.randomUUID())
                .setRule("anomaly.spike").setTitle("Всплеск").setExpectedSavings(new BigDecimal("448.123456"))
                .setCurrency("RUB").setEntityIds(entities).setDetectedAt(Instant.now()).build();
    }

    @Test
    void tiesTheFindingToItsAgent() {
        UUID agent = UUID.randomUUID();
        new FindingInbox(intake).handle(finding(List.of("provider:agent " + agent, "agent:" + agent)));
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.agent()).isEqualTo(agent);
            assertThat(c.subscription()).isNull();
            assertThat(c.savings()).isEqualByComparingTo("448.12");
        });
    }

    @Test
    void aBrokenEntityIdIsIgnored() {
        new FindingInbox(intake).handle(finding(List.of("agent:not-a-uuid")));
        assertThat(calls).singleElement().satisfies(c -> assertThat(c.agent()).isNull());
    }

    @Test
    void anEnglishCompanyReadsTheDetectorsTitleInEnglish() {
        Finding spike = finding(List.of());
        spike.setTitle("Всплеск трат: Figma, 812.40 USD за 12.11.2026 при обычных 95.10");
        UUID english = spike.getTenantId();

        new FindingInbox(intake, t -> t.equals(english) ? ai.costguard.core.shared.names.Language.EN
                : ai.costguard.core.shared.names.Language.RU).handle(spike);
        new FindingInbox(intake).handle(spike);

        assertThat(calls).extracting(Call::title).containsExactly(
                "Spend spike: Figma, 812.40 USD on 2026-11-12 against the usual 95.10",
                "Всплеск трат: Figma, 812.40 USD за 12.11.2026 при обычных 95.10");
    }
}
