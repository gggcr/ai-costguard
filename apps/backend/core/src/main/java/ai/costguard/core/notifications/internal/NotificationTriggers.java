package ai.costguard.core.notifications.internal;

import ai.costguard.core.agents.AgentRiskChanged;
import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.findings.FindingOpened;
import ai.costguard.core.findings.SavingsConfirmed;
import ai.costguard.core.org.EmployeeDismissed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns events of other modules into the feed, and into e-mail and Telegram for the people they concern (ADR 0031).
 * Each listener runs after the source transaction commits, in its own transaction, and the outbox retries it if it
 * fails.
 */
@Component
class NotificationTriggers {

    private static final int REASONS = 3;

    private final NotificationService service;

    NotificationTriggers(NotificationService service) {
        this.service = service;
    }

    private static String rub(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽";
    }

    @ApplicationModuleListener
    void on(ApprovalEscalated e) {
        service.notify(e.tenantId(), "Согласование на " + rub(e.amount()) + " ждёт решения, срок истекает",
                "/approvals", Level.warning, Audience.roles("owner", "finance").andOwnerOf(e.agentId()));
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        if ("expired".equals(e.status())) {
            service.notify(e.tenantId(), "Срок согласования на " + rub(e.amount()) + " истёк, резерв возвращён",
                    "/approvals", Level.warning, Audience.roles("owner", "finance").andOwnerOf(e.agentId()));
        }
    }

    @ApplicationModuleListener
    void on(AgentStateChanged e) {
        if (e.suspended()) {
            service.notify(e.tenantId(), "Агент остановлен. Шлюз отвечает 423 agent_suspended",
                    "/agents/" + e.agentId(), Level.critical,
                    Audience.roles("owner", "it_admin").andOwnerOf(e.agentId()));
        }
    }

    /**
     * The detector changed what the gateway does with an agent or task. Stricter: warning, or critical for STOP;
     * back to NORMAL: good. The owner of the agent, owners and IT administrators are told.
     */
    @ApplicationModuleListener
    void on(AgentRiskChanged e) {
        String who = "«" + e.agentName() + "»" + (e.taskId() == null ? "" : ", задача " + e.taskId());
        String why = why(e.reasons());
        String text;
        Level level;
        switch (e.action()) {
            case "STOP" -> {
                text = "Детектор аномалий остановил агента " + who + ": риск " + e.score() + why
                        + ". Шлюз отвечает 423, пока риск не снизится";
                level = Level.critical;
            }
            case "REQUIRE_APPROVAL" -> {
                text = "Агент " + who + ": каждая операция теперь требует согласования, риск " + e.score() + why;
                level = Level.warning;
            }
            case "SLOW_DOWN" -> {
                text = "Агент " + who + " замедлен шлюзом, риск " + e.score() + why;
                level = Level.warning;
            }
            default -> {
                if (AgentRiskChanged.rank(e.previousAction()) == 0) {
                    return;
                }
                text = "Агент " + who + " снова работает без ограничений: риск " + e.score();
                level = Level.good;
            }
        }
        service.notify(e.tenantId(), text, "/agents/" + e.agentId(), level,
                Audience.roles("owner", "it_admin").andEmployee(e.ownerId()));
    }

    private static String why(List<String> reasons) {
        if (reasons.isEmpty()) {
            return "";
        }
        List<String> top = reasons.size() > REASONS ? reasons.subList(0, REASONS) : reasons;
        return " (" + String.join("; ", top) + ")";
    }

    @ApplicationModuleListener
    void on(EmployeeDismissed e) {
        service.notify(e.tenantId(), "Отмечено увольнение. Проверьте чек-лист: техника и лицензии",
                "/org?employee=" + e.employeeId(), Level.info, Audience.roles("owner", "it_admin", "finance"));
    }

    @ApplicationModuleListener
    void on(FindingOpened e) {
        service.notify(e.tenantId(), "Новая находка: " + e.title() + ", " + rub(e.expectedSavings()) + "/мес",
                "/findings/" + e.findingId(), Level.info, Audience.roles("owner", "finance", "it_admin"));
    }

    @ApplicationModuleListener
    void on(SavingsConfirmed e) {
        service.notify(e.tenantId(), "Подтверждена экономия " + rub(e.monthlySavings()) + "/мес: " + e.title(),
                "/findings/" + e.findingId(), Level.good, Audience.roles("owner", "finance"));
    }
}
