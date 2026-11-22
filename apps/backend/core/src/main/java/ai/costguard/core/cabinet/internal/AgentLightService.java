package ai.costguard.core.cabinet.internal;

import ai.costguard.core.agents.AgentOwners;
import ai.costguard.core.approvals.ApprovalOpened;
import ai.costguard.core.approvals.OwnerApprovals;
import ai.costguard.core.ledger.AgentBudgets;
import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.DepartmentHeads;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.shared.names.Currencies;
import ai.costguard.core.shared.names.Language;
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
        Language lang = lang();
        List<MyAgent> out = agents.stream().map(a -> light(a, budgets.ofAgent(a.id()), pending.get(a.id()),
                waiting.getOrDefault(a.id(), List.of()), lang)).toList();
        return new MyAgents(out, mine);
    }

    static MyAgent light(AgentOwners.OwnedAgent a, Optional<AgentBudgets.AgentBudget> budget, LimitRequest request,
            List<OwnerApprovals.Waiting> waiting, Language lang) {
        Integer percent = budget.map(b -> b.limit().signum() == 0 ? (b.spent().signum() > 0 ? 100 : 0)
                : b.spent().multiply(BigDecimal.valueOf(100)).divide(b.limit(), 0, RoundingMode.DOWN).intValue())
                .orElse(null);
        Light light;
        String why;
        if ("suspended".equals(a.status())) {
            light = Light.red;
            why = lang.pick("Агент приостановлен администратором", "An administrator suspended the agent");
        } else if ("STOP".equals(a.riskAction())) {
            light = Light.red;
            why = lang.pick("Агента остановил детектор аномалий: ", "The anomaly detector stopped the agent: ")
                    + reason(a.riskReason(), lang);
        } else if (percent != null && percent >= 100) {
            light = Light.red;
            why = lang.pick("Лимит исчерпан: шлюз не пропускает новые операции. Попросите продлить лимит",
                    "The limit is used up: the gateway lets no new operations through. Ask for more");
        } else if (!waiting.isEmpty()) {
            light = Light.yellow;
            why = lang.pick("Операции ждут вашего решения: ", "Operations waiting for your decision: ")
                    + waiting.size();
        } else if ("REQUIRE_APPROVAL".equals(a.riskAction()) || "SLOW_DOWN".equals(a.riskAction())) {
            light = Light.yellow;
            why = ("REQUIRE_APPROVAL".equals(a.riskAction())
                    ? lang.pick("Каждая операция требует подтверждения: ", "Every operation needs your confirmation: ")
                    : lang.pick("Шлюз замедлил агента: ", "The gateway slowed the agent down: "))
                    + reason(a.riskReason(), lang);
        } else if (percent != null && percent >= YELLOW_PERCENT) {
            light = Light.yellow;
            why = lang.pick("Израсходовано " + percent + "% лимита", percent + "% of the limit is spent");
        } else {
            light = Light.green;
            why = percent == null
                    ? lang.pick("Своего бюджета у агента нет: тратит из бюджета отдела",
                            "The agent has no budget of its own: it spends the department's")
                    : lang.pick("В лимите: израсходовано " + percent + "%", "Within the limit: " + percent + "% spent");
        }
        return new MyAgent(a.id(), a.name(), a.status(), a.riskAction(), a.riskScore(), a.riskReason(), light, why,
                budget.map(AgentBudgets.AgentBudget::limit).map(BigDecimal::stripTrailingZeros).orElse(null),
                budget.map(AgentBudgets.AgentBudget::spent).map(BigDecimal::stripTrailingZeros).orElse(null), percent,
                budget.map(AgentBudgets.AgentBudget::currency).orElse(null), request,
                waiting.stream().map(w -> new WaitingOperation(w.id(), w.description(), w.amount(), w.reason(),
                        w.createdAt(), w.deadline())).toList());
    }

    private static String reason(String r, Language lang) {
        return r == null || r.isBlank() ? lang.pick("подозрительная активность", "suspicious activity") : r;
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
        Language lang = lang();
        String name = employees.card(me).map(EmployeeDirectory.EmployeeCard::name)
                .orElse(lang.pick("Сотрудник", "An employee"));
        String more = money(request.add(), budget.currency());
        heads.headOf(me).ifPresent(head -> notices.send(tenantId, head, lang.pick(
                name + " просит продлить лимит агента «" + agent.name() + "» на " + more + ": ",
                name + " asks to raise the limit of agent «" + agent.name() + "» by " + more + ": ")
                + request.reason().strip(),
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
            String limit = money(raised.limit(), raised.currency());
            text = lang().pick("Лимит агента «" + r.agentName() + "» продлён: теперь " + limit,
                    "The limit of agent «" + r.agentName() + "» was raised: now " + limit);
        } else {
            text = lang().pick("Лимит агента «" + r.agentName() + "» не продлён",
                    "The limit of agent «" + r.agentName() + "» was not raised");
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
        // the decision names the account of the token's subject, not the token's name (finding H2 of 2026-11-12)
        UUID account = whoami.account(jwt).id();
        try {
            OwnerApprovals.Decided d = approvals.decide(tenantId, approvalId, me, account, decision.approve(),
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
        Language lang = lang();
        owners.ownerOf(e.agentId()).ifPresent(owner -> {
            String agent = owners.ownedBy(owner).stream().filter(a -> a.id().equals(e.agentId()))
                    .map(AgentOwners.OwnedAgent::name).findFirst().orElse(lang.pick("агент", "agent"));
            String why = "risk".equals(e.reason())
                    ? lang.pick("детектор аномалий счёл операцию необычной", "the anomaly detector found it unusual")
                    : lang.pick("сумма выше порога мандата", "the amount is above the mandate's threshold");
            // the gateway prices an operation in the currency of the agent's budget, the company's (ADR 0072)
            String currency = budgets.ofAgent(e.agentId()).map(AgentBudgets.AgentBudget::currency)
                    .or(() -> employees.company().map(EmployeeDirectory.CompanyCard::currency)).orElse(null);
            String sum = money(e.amount(), currency);
            notices.send(e.tenantId(), owner, lang.pick(
                    "Агент «" + agent + "» хочет потратить " + sum + " (" + e.description() + "): " + why
                            + ". Подтвердите или заблокируйте",
                    "Agent «" + agent + "» wants to spend " + sum + " (" + e.description() + "): " + why
                            + ". Confirm or block it"), "/cabinet/agents", "warning");
        });
    }

    /** The company's language (ADR 0074); the session is bound. */
    private Language lang() {
        return employees.company().map(EmployeeDirectory.CompanyCard::language).orElse(Language.RU);
    }

    private static String money(BigDecimal amount, String currency) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + Currencies.suffix(currency);
    }
}
