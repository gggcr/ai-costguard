package ai.costguard.core.org.internal;

import ai.costguard.core.org.EmployeeDismissed;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Уволили — доступы закрываются сами (ADR 0060): the person's CostGuard accounts are disabled in Keycloak and, when
 * the company makes mailboxes, their Microsoft 365 account is disabled and its sessions end. The mailbox and the
 * accounts stay for the handover and the history; erasure removes them later. What could not be done is a step for
 * a person in the activity log.
 */
@Component
class Offboarding {

    private static final Logger log = LoggerFactory.getLogger(Offboarding.class);

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final CompanyLoginService logins;
    private final IdentityProvider accounts;
    private final MicrosoftDirectory microsoft;
    private final ApplicationEventPublisher events;

    Offboarding(JdbcClient jdbc, TenantSession session, TransactionTemplate transactions, CompanyLoginService logins,
            IdentityProvider accounts, MicrosoftDirectory microsoft, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.session = session;
        this.transactions = transactions;
        this.logins = logins;
        this.accounts = accounts;
        this.microsoft = microsoft;
        this.events = events;
    }

    private record Found(Set<String> emails, Optional<CompanyLoginService.Mailboxes> mailboxes) {
    }

    @ApplicationModuleListener
    void on(EmployeeDismissed e) {
        Found found = transactions.execute(tx -> {
            session.bind(e.tenantId());
            Set<String> emails = new LinkedHashSet<>();
            jdbc.sql("select email from employee where id = ?").param(e.employeeId()).query(String.class).optional()
                    .ifPresent(m -> emails.add(m.toLowerCase(Locale.ROOT)));
            jdbc.sql("select email from user_account where employee_id = ?").param(e.employeeId())
                    .query(String.class).list().forEach(m -> emails.add(m.toLowerCase(Locale.ROOT)));
            return new Found(emails, logins.mailboxes(e.tenantId()));
        });
        int disabledAccounts = 0;
        int disabledMailboxes = 0;
        List<String> failed = new java.util.ArrayList<>();
        for (String email : found.emails()) {
            if (accounts.configured()) {
                try {
                    Optional<IdentityProvider.Account> account = accounts.byEmail(email)
                            .filter(a -> e.tenantId().equals(a.tenantId()));
                    if (account.isPresent()) {
                        accounts.disable(account.get().id());
                        disabledAccounts++;
                    }
                } catch (IdentityProvider.Unavailable x) {
                    log.warn("Dismissal: Keycloak account of {} stays enabled: {}", e.employeeId(), x.getMessage());
                    failed.add("costguard:" + email);
                }
            }
            if (found.mailboxes().isPresent() && email.endsWith("@" + found.mailboxes().get().domain())) {
                try {
                    microsoft.disable(found.mailboxes().get().app(), email);
                    disabledMailboxes++;
                } catch (MicrosoftDirectory.Unavailable x) {
                    log.warn("Dismissal: Microsoft 365 account of {} stays enabled: {}", e.employeeId(),
                            x.getMessage());
                    failed.add("microsoft:" + email);
                }
            }
        }
        final int a = disabledAccounts;
        final int m = disabledMailboxes;
        transactions.executeWithoutResult(tx -> {
            session.bind(e.tenantId());
            events.publishEvent(ActionTaken.of(e.tenantId(), failed.isEmpty() ? "employee.access.closed"
                    : "employee.access.close_failed", "employee", e.employeeId(),
                    Map.of("accounts", a, "microsoft", m, "failed", String.join(",", failed))));
        });
    }
}
