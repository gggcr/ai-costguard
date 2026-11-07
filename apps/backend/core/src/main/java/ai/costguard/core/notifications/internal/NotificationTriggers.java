package ai.costguard.core.notifications.internal;

import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.findings.FindingOpened;
import ai.costguard.core.findings.SavingsConfirmed;
import ai.costguard.core.org.EmployeeDismissed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns events of other modules into the feed. Each listener runs after the source transaction commits, in its own
 * transaction, and the outbox retries it if it fails.
 */
@Component
class NotificationTriggers {

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
                "/approvals", Level.warning);
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        if ("expired".equals(e.status())) {
            service.notify(e.tenantId(), "Срок согласования на " + rub(e.amount()) + " истёк, резерв возвращён",
                    "/approvals", Level.warning);
        }
    }

    @ApplicationModuleListener
    void on(AgentStateChanged e) {
        if (e.suspended()) {
            service.notify(e.tenantId(), "Агент остановлен. Шлюз отвечает 423 agent_suspended",
                    "/agents/" + e.agentId(), Level.critical);
        }
    }

    @ApplicationModuleListener
    void on(EmployeeDismissed e) {
        service.notify(e.tenantId(), "Отмечено увольнение. Проверьте чек-лист: техника и лицензии",
                "/org?employee=" + e.employeeId(), Level.info);
    }

    @ApplicationModuleListener
    void on(FindingOpened e) {
        service.notify(e.tenantId(), "Новая находка: " + e.title() + ", " + rub(e.expectedSavings()) + "/мес",
                "/findings/" + e.findingId(), Level.info);
    }

    @ApplicationModuleListener
    void on(SavingsConfirmed e) {
        service.notify(e.tenantId(), "Подтверждена экономия " + rub(e.monthlySavings()) + "/мес: " + e.title(),
                "/findings/" + e.findingId(), Level.good);
    }
}
