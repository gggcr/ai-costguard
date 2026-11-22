package ai.costguard.core.notifications.internal;

import ai.costguard.core.agents.AgentRiskChanged;
import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.findings.FindingOpened;
import ai.costguard.core.findings.SavingsConfirmed;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.EmployeeDismissed;
import ai.costguard.core.plans.PlanAccessEnded;
import ai.costguard.core.plans.PlanAccessEnding;
import ai.costguard.core.shared.names.Currencies;
import ai.costguard.core.shared.names.Language;
import ai.costguard.core.shared.names.TimeZones;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns events of other modules into the feed, and into e-mail and Telegram for the people they concern (ADR 0031).
 * Each listener runs after the source transaction commits, in its own transaction, and the outbox retries it if it
 * fails. Texts are in the company's language (ADR 0074), amounts in its currency (ADR 0072).
 */
@Component
class NotificationTriggers {

    private static final int REASONS = 3;
    private static final DateTimeFormatter DAY_RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DAY_EN = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final NotificationService service;
    private final TenantSession session;
    private final EmployeeDirectory company;

    NotificationTriggers(NotificationService service, TenantSession session, EmployeeDirectory company) {
        this.service = service;
        this.session = session;
        this.company = company;
    }

    private Optional<EmployeeDirectory.CompanyCard> card(UUID tenantId) {
        session.bind(tenantId);
        return company.company();
    }

    private Language lang(UUID tenantId) {
        return card(tenantId).map(EmployeeDirectory.CompanyCard::language).orElse(Language.RU);
    }

    /** An amount of the company, in its currency (ADR 0072): approvals and savings are kept in it. */
    private String money(UUID tenantId, BigDecimal amount) {
        String currency = card(tenantId).map(EmployeeDirectory.CompanyCard::currency).orElse(Currencies.DEFAULT);
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString() + Currencies.suffix(currency);
    }

    @ApplicationModuleListener
    void on(ApprovalEscalated e) {
        String sum = money(e.tenantId(), e.amount());
        service.notify(e.tenantId(), lang(e.tenantId()).pick(
                        "Согласование на " + sum + " ждёт решения, срок истекает",
                        "An approval of " + sum + " is waiting for a decision, and its time is running out"),
                "/approvals", Level.warning, Audience.roles("owner", "finance").andOwnerOf(e.agentId()));
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        if ("expired".equals(e.status())) {
            String sum = money(e.tenantId(), e.amount());
            service.notify(e.tenantId(), lang(e.tenantId()).pick(
                            "Срок согласования на " + sum + " истёк, резерв возвращён",
                            "The approval of " + sum + " expired; the reserve was released"),
                    "/approvals", Level.warning, Audience.roles("owner", "finance").andOwnerOf(e.agentId()));
        }
    }

    @ApplicationModuleListener
    void on(AgentStateChanged e) {
        if (e.suspended()) {
            service.notify(e.tenantId(), lang(e.tenantId()).pick(
                            "Агент остановлен. Шлюз отвечает 423 agent_suspended",
                            "The agent is suspended. The gateway answers 423 agent_suspended"),
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
        Language lang = lang(e.tenantId());
        String who = "«" + e.agentName() + "»"
                + (e.taskId() == null ? "" : lang.pick(", задача ", ", task ") + e.taskId());
        String why = why(e.reasons());
        String text;
        Level level;
        switch (e.action()) {
            case "STOP" -> {
                text = lang.pick("Детектор аномалий остановил агента " + who + ": риск " + e.score() + why
                                + ". Шлюз отвечает 423, пока риск не снизится",
                        "The anomaly detector stopped agent " + who + ": risk " + e.score() + why
                                + ". The gateway answers 423 until the risk goes down");
                level = Level.critical;
            }
            case "REQUIRE_APPROVAL" -> {
                text = lang.pick("Агент " + who + ": каждая операция теперь требует согласования, риск " + e.score()
                                + why,
                        "Agent " + who + ": every operation now needs an approval, risk " + e.score() + why);
                level = Level.warning;
            }
            case "SLOW_DOWN" -> {
                text = lang.pick("Агент " + who + " замедлен шлюзом, риск " + e.score() + why,
                        "Agent " + who + " is slowed down by the gateway, risk " + e.score() + why);
                level = Level.warning;
            }
            default -> {
                if (AgentRiskChanged.rank(e.previousAction()) == 0) {
                    return;
                }
                text = lang.pick("Агент " + who + " снова работает без ограничений: риск " + e.score(),
                        "Agent " + who + " works without limits again: risk " + e.score());
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
        service.notify(e.tenantId(), lang(e.tenantId()).pick(
                        "Отмечено увольнение. Проверьте чек-лист: техника и лицензии",
                        "A dismissal was recorded. Check the checklist: hardware and licenses"),
                "/org?employee=" + e.employeeId(), Level.info, Audience.roles("owner", "it_admin", "finance"));
    }

    @ApplicationModuleListener
    void on(FindingOpened e) {
        String sum = money(e.tenantId(), e.expectedSavings());
        service.notify(e.tenantId(), lang(e.tenantId()).pick(
                        "Новая находка: " + e.title() + ", " + sum + "/мес",
                        "New finding: " + e.title() + ", " + sum + "/mo"),
                "/findings/" + e.findingId(), Level.info, Audience.roles("owner", "finance", "it_admin"));
    }

    @ApplicationModuleListener
    void on(SavingsConfirmed e) {
        String sum = money(e.tenantId(), e.monthlySavings());
        service.notify(e.tenantId(), lang(e.tenantId()).pick(
                        "Подтверждена экономия " + sum + "/мес: " + e.title(),
                        "Savings of " + sum + "/mo confirmed: " + e.title()),
                "/findings/" + e.findingId(), Level.good, Audience.roles("owner", "finance"));
    }

    /** Owners are warned before the trial or the paid period ends, and told when it ended (ADR 0037). */
    @ApplicationModuleListener
    void on(PlanAccessEnding e) {
        Optional<EmployeeDirectory.CompanyCard> card = card(e.tenantId());
        ZoneId zone = card.map(EmployeeDirectory.CompanyCard::zone).orElse(TimeZones.LEGACY);
        Language lang = card.map(EmployeeDirectory.CompanyCard::language).orElse(Language.RU);
        String day = (lang == Language.RU ? DAY_RU : DAY_EN).format(e.accessUntil().atZone(zone));
        String text = e.trial()
                ? lang.pick("Пробный период заканчивается " + day
                                + ". Выберите тариф, чтобы не потерять возможность вносить изменения",
                        "The trial ends on " + day + ". Choose a plan to keep making changes")
                : lang.pick("Оплаченный период заканчивается " + day
                                + ". Продлите тариф, чтобы не потерять возможность вносить изменения",
                        "The paid period ends on " + day + ". Renew the plan to keep making changes");
        service.notify(e.tenantId(), text, "/settings/plan", Level.warning, Audience.roles("owner"));
    }

    @ApplicationModuleListener
    void on(PlanAccessEnded e) {
        Language lang = lang(e.tenantId());
        String text = e.trial()
                ? lang.pick("Пробный период закончился", "The trial is over")
                : lang.pick("Оплаченный период закончился", "The paid period is over");
        text += lang.pick(". Данные доступны только для просмотра, агенты остановлены шлюзом, пока тариф не оплачен",
                ". The data is read-only, and the gateway stops the agents until the plan is paid");
        service.notify(e.tenantId(), text, "/settings/plan", Level.critical, Audience.roles("owner"));
    }
}
