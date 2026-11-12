package ai.costguard.core.cabinet.internal;

import ai.costguard.core.agents.AgentOwners;
import ai.costguard.core.approvals.ApprovalOpened;
import ai.costguard.core.approvals.OwnerApprovals;
import ai.costguard.core.ledger.AgentBudgets;
import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.DepartmentHeads;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Бюджет-светофор и антифрод для сотрудника (ADR 0067). The owner of an agent sees its colour: green in the limit,
 * yellow at 80% or when the anomaly detector is wary, red when the gateway stops it. One button asks to raise the
 * limit with a short reason; the head of the owner's department (or owner, finance) decides. An operation the gateway
 * held back reaches the owner first: they confirm it (NEAR keeps the confirmation) or block it.
 */
@Service
class AgentLightService {

    static final int YELLOW_PERCENT = 80;

    private final AgentOwners owners;
    private final AgentBudgets budgets;
    private final OwnerApprovals approvals;
    private final LimitRequestRepository requests;
    private final DepartmentHeads heads;
    private final EmployeeDirectory employees;
    private final PersonalNotices notices;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AgentLightService(AgentOwners owners, AgentBudgets budgets, OwnerApprovals approvals,
            LimitRequestRepository requests, DepartmentHeads heads, EmployeeDirectory employees,
            PersonalNotices notices, Whoami whoami, TenantSession session, ApplicationEventPublisher events,
            Clock clock) {
        this.owners = owners;
        this.budgets = budgets;
        this.approvals = approvals;
        this.requests = requests;
        this.heads = heads;
        this.employees = employees;
        this.notices = notices;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    MyAgents mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        List<AgentOwners.OwnedAgent> agents = owners.ownedBy(me);
        Map<UUID, List<OwnerApprovals.Waiting>> waiting = approvals
                .waiting(agents.stream().map(AgentOwners.OwnedAgent::id).toList()).stream()
                .collect(Collectors.groupingBy(OwnerApprovals.Waiting::agentId));
        List<LimitRequest> mine = requests.of(me);
        Map<UUID, LimitRequest> pending = mine.stream().filter(r -> r.status().equals("pending"))
                .collect(Collectors.toMap(LimitRequest::agentId, Function.identity(), (a, b) -> a));
        List<MyAgent> out = agents.stream().map(a -> light(a, budgets.ofAgent(a.id()), pending.get(a.id()),
                waiting.getOrDefault(a.id(), List.of()))).toList();
        return new MyAgents(out, mine);
    }

    static MyAgent light(AgentOwners.OwnedAgent a, Optional<AgentBudgets.AgentBudget> budget, LimitRequest request,
            List<OwnerApprovals.Waiting> waiting) {
        Integer percent = budget.map(b -> b.limit().signum() == 0 ? (b.spent().signum() > 0 ? 100 : 0)
                : b.spent().multiply(BigDecimal.valueOf(100)).divide(b.limit(), 0, RoundingMode.DOWN).intValue())
                .orElse(null);
        Light light;
        String why;
        if ("suspended".equals(a.status())) {
            light = Light.red;
            why = "Агент приостановлен администратором";
        } else if ("STOP".equals(a.riskAction())) {
            light = Light.red;
            why = "Агента остановил детектор аномалий: " + reason(a.riskReason());
        } else if (percent != null && percent >= 100) {
            light = Light.red;
            why = "Лимит исчерпан: шлюз не пропускает новые операции. Попросите продлить лимит";
        } else if (!waiting.isEmpty()) {
            light = Light.yellow;
            why = "Операции ждут вашего решения: " + waiting.size();
        } else if ("REQUIRE_APPROVAL".equals(a.riskAction()) || "SLOW_DOWN".equals(a.riskAction())) {
            light = Light.yellow;
            why = ("REQUIRE_APPROVAL".equals(a.riskAction()) ? "Каждая операция требует подтверждения: "
                    : "Шлюз замедлил агента: ") + reason(a.riskReason());
        } else if (percent != null && percent >= YELLOW_PERCENT) {
            light = Light.yellow;
            why = "Израсходовано " + percent + "% лимита";
        } else {
            light = Light.green;
            why = percent == null ? "Своего бюджета у агента нет: тратит из бюджета отдела"
                    : "В лимите: израсходовано " + percent + "%";
        }
        return new MyAgent(a.id(), a.name(), a.status(), a.riskAction(), a.riskScore(), a.riskReason(), light, why,
                budget.map(AgentBudgets.AgentBudget::limit).map(BigDecimal::stripTrailingZeros).orElse(null),
                budget.map(AgentBudgets.AgentBudget::spent).map(BigDecimal::stripTrailingZeros).orElse(null), percent,
                budget.map(AgentBudgets.AgentBudget::currency).orElse(null), request,
                waiting.stream().map(w -> new WaitingOperation(w.id(), w.description(), w.amount(), w.reason(),
                        w.createdAt(), w.deadline())).toList());
    }

    private static String reason(String r) {
        return r == null || r.isBlank() ? "подозрительная активность" : r;
    }

    @Transactional
    LimitRequest askLimit(UUID tenantId, Jwt jwt, UUID agentId, NewLimitRequest request) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        AgentOwners.OwnedAgent agent = owners.ownedBy(me).stream().filter(a -> a.id().equals(agentId)).findFirst()
                .orElseThrow(CabinetProblem.AGENT_NOT_FOUND::failure);
        AgentBudgets.AgentBudget budget = budgets.ofAgent(agentId)
                .orElseThrow(CabinetProblem.AGENT_NO_BUDGET::failure);
        if (requests.pendingFor(agentId)) {
            throw CabinetProblem.LIMIT_ASKED.failure();
        }
        UUID id = UUID.randomUUID();
        requests.insert(tenantId, id, me, agentId, budget.budgetId(), budget.limit(), budget.spent(), request.add(),
                budget.currency(), request.reason(), clock.instant());
        String name = employees.card(me).map(EmployeeDirectory.EmployeeCard::name).orElse("Сотрудник");
        heads.headOf(me).ifPresent(head -> notices.send(tenantId, head, name + " просит продлить лимит агента «"
                + agent.name() + "» на " + money(request.add(), budget.currency()) + ": " + request.reason().strip(),
                "/cabinet/agents", "warning"));
        events.publishEvent(ActionTaken.of(tenantId, "agent.limit.requested", "limit_request", id,
                ActionTaken.details("employeeId", me, "agentId", agentId, "add", request.add().toPlainString())));
        return requests.find(id).orElseThrow();
    }

    @Transactional
    void cancel(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        LimitRequest r = requests.lock(id).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.LIMIT_REQUEST_NOT_FOUND::failure);
        if (!r.status().equals("pending")) {
            throw CabinetProblem.LIMIT_REQUEST_DECIDED.failure();
        }
        requests.decide(id, "cancelled", Whoami.who(jwt), clock.instant(), null);
    }

    @Transactional(readOnly = true)
    List<LimitRequest> team(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        return requests.ofEmployees(heads.headedBy(whoami.employee(jwt)));
    }

    @Transactional
    LimitRequest decideAsHead(UUID tenantId, Jwt jwt, UUID id, LimitDecision decision) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        LimitRequest r = requests.lock(id).orElseThrow(CabinetProblem.LIMIT_REQUEST_NOT_FOUND::failure);
        if (!heads.headOf(r.employeeId()).map(me::equals).orElse(false)) {
            throw CabinetProblem.NOT_THE_HEAD.failure();
        }
        return decide(tenantId, r, decision, Whoami.who(jwt));
    }

    @Transactional(readOnly = true)
    List<LimitRequest> all(UUID tenantId) {
        session.bind(tenantId);
        return requests.all();
    }

    @Transactional
    LimitRequest decideAsEmployer(UUID tenantId, UUID id, LimitDecision decision, String by) {
        session.bind(tenantId);
        return decide(tenantId, requests.lock(id).orElseThrow(CabinetProblem.LIMIT_REQUEST_NOT_FOUND::failure),
                decision, by);
    }

    private LimitRequest decide(UUID tenantId, LimitRequest r, LimitDecision decision, String by) {
        if (!r.status().equals("pending")) {
            throw CabinetProblem.LIMIT_REQUEST_DECIDED.failure();
        }
        String text;
        if (decision.approve()) {
            AgentBudgets.AgentBudget raised = budgets.raise(tenantId, r.budgetId(), r.add());
            text = "Лимит агента «" + r.agentName() + "» продлён: теперь " + money(raised.limit(), raised.currency());
        } else {
            text = "Лимит агента «" + r.agentName() + "» не продлён";
        }
        requests.decide(r.id(), decision.approve() ? "approved" : "rejected", by, clock.instant(), decision.comment());
        String note = decision.comment() == null || decision.comment().isBlank() ? "" : ": " + decision.comment().strip();
        notices.send(tenantId, r.employeeId(), text + note, "/cabinet/agents", decision.approve() ? "good" : "warning");
        events.publishEvent(ActionTaken.of(tenantId, decision.approve() ? "agent.limit.approved"
                : "agent.limit.rejected", "limit_request", r.id(), ActionTaken.details("employeeId", r.employeeId(),
                "agentId", r.agentId(), "add", r.add().toPlainString())));
        return requests.find(r.id()).orElseThrow();
    }

    /** Confirm or block an operation of my agent that the gateway held back. */
    @Transactional
    OperationDecided decideOperation(UUID tenantId, Jwt jwt, UUID approvalId, OperationDecision decision) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        try {
            OwnerApprovals.Decided d = approvals.decide(tenantId, approvalId, me, Whoami.who(jwt), decision.approve(),
                    decision.comment()).orElseThrow(CabinetProblem.OPERATION_NOT_FOUND::failure);
            return new OperationDecided(d.id(), d.status(), d.notaryHash());
        } catch (OwnerApprovals.AlreadyDecided e) {
            throw CabinetProblem.OPERATION_DECIDED.failure();
        }
    }

    /** The owner hears at once that an operation of their agent waits for them. */
    @ApplicationModuleListener
    void on(ApprovalOpened e) {
        session.bind(e.tenantId());
        owners.ownerOf(e.agentId()).ifPresent(owner -> {
            String agent = owners.ownedBy(owner).stream().filter(a -> a.id().equals(e.agentId()))
                    .map(AgentOwners.OwnedAgent::name).findFirst().orElse("агент");
            String why = "risk".equals(e.reason()) ? "детектор аномалий счёл операцию необычной"
                    : "сумма выше порога мандата";
            notices.send(e.tenantId(), owner, "Агент «" + agent + "» хочет потратить "
                    + money(e.amount(), "RUB") + " (" + e.description() + "): " + why
                    + ". Подтвердите или заблокируйте", "/cabinet/agents", "warning");
        });
    }

    private static String money(BigDecimal amount, String currency) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount)
                + (currency == null || "RUB".equals(currency) ? " ₽" : " " + currency);
    }
}
