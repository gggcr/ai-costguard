package ai.costguard.core.shared.inbox;

import ai.costguard.core.agents.AgentRisks;
import ai.costguard.core.shared.names.DetectorText;
import ai.costguard.core.shared.names.Language;
import ai.costguard.events.AgentRiskSignal;
import java.util.UUID;
import java.util.function.Function;

/**
 * Risk of agents and tasks from the anomaly detector, {@code agent.risk-signals.v1} (ADR 0031). The topic is
 * compacted: a tombstone (an erased agent) has no value and is skipped. The reasons come in Russian and are kept in
 * the company's language (ADR 0074), as people read them on the agent, in the cabinet and in notifications.
 */
class RiskSignalInbox {

    private final AgentRisks risks;
    private final Function<UUID, Language> languages;

    RiskSignalInbox(AgentRisks risks) {
        this(risks, tenant -> Language.RU);
    }

    RiskSignalInbox(AgentRisks risks, Function<UUID, Language> languages) {
        this.risks = risks;
        this.languages = languages;
    }

    void handle(AgentRiskSignal s) {
        Language language = languages.apply(s.getTenantId());
        risks.signal(s.getTenantId(), s.getAgentId(), s.getTaskId(), s.getScore(), s.getAction().name(),
                s.getReasons().stream().map(r -> DetectorText.in(language, r)).toList(), s.getDetectedAt());
    }
}
