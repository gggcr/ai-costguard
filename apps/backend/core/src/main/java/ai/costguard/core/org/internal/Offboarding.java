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
 *
 * <p>Only the person's own logins are closed: the Keycloak accounts of the CostGuard accounts linked to the employee
 * (by their {@code keycloak_subject}) and their addresses. The address on the employee card, which an it_admin edits,
 * closes a Microsoft 365 account only when no one else in the company has it in any case; otherwise an it_admin could
 * put an owner's address on someone and dismiss them to lock the owner out (finding of 2026-11-13).
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

    /** A CostGuard account of the dismissed person: linked to the employee by an owner, an invitation or onboarding. */
    private record Login(String email, String subject) {
    }

    /**
     * What to close. {@code addresses} are the Microsoft 365 addresses that surely belong to the person; an address
     * of the employee card that someone else also has is in {@code ambiguous} and closes nothing.
     */
    private record Found(List<Login> logins, Set<String> addresses, Set<String> ambiguous,
            Optional<CompanyLoginService.Mailboxes> mailboxes) {
    }

    @ApplicationModuleListener
    void on(EmployeeDismissed e) {
        Found found = transactions.execute(tx -> {
            session.bind(e.tenantId());
            // Who the person is comes from their accounts (finding of 2026-11-13): the address on the employee card
            // is edited by an it_admin, so an address put there must never close somebody else's access.
            List<Login> own = jdbc.sql("select email, keycloak_subject from user_account where employee_id = ?")
                    .param(e.employeeId())
                    .query((rs, n) -> new Login(rs.getString(1), rs.getString(2))).list();
            Set<String> addresses = new LinkedHashSet<>();
            own.forEach(l -> addresses.add(l.email().toLowerCase(Locale.ROOT)));
            Set<String> ambiguous = new LinkedHashSet<>();
            jdbc.sql("select email from employee where id = ?").param(e.employeeId()).query(String.class).optional()
                    .map(m -> m.toLowerCase(Locale.ROOT))
                    .filter(m -> !addresses.contains(m))
                    .ifPresent(m -> (sharedWithSomeoneElse(e.employeeId(), m) ? ambiguous : addresses).add(m));
            return new Found(own, addresses, ambiguous, logins.mailboxes(e.tenantId()));
        });
        int disabledAccounts = 0;
        int disabledMailboxes = 0;
        List<String> failed = new java.util.ArrayList<>();
        if (!found.ambiguous().isEmpty()) {
            // a step for a person: the card's address is someone else's too, so nothing was closed by it
            log.warn("Dismissal: the address of employee {} belongs to someone else too; nothing closed by it",
                    e.employeeId());
            failed.add("ambiguous_address");
        }
        if (accounts.configured()) {
            Set<String> done = new java.util.HashSet<>();
            for (Login login : found.logins()) {
                try {
                    Optional<IdentityProvider.Account> account = login.subject() != null
                            ? accounts.byId(login.subject())
                            : accounts.byEmail(login.email()).filter(a -> !linkedElsewhere(e.tenantId(), a.id()));
                    account = account.filter(a -> e.tenantId().equals(a.tenantId()));
                    if (account.isPresent() && done.add(account.get().id())) {
                        accounts.disable(account.get().id());
                        disabledAccounts++;
                    }
                } catch (IdentityProvider.Unavailable x) {
                    log.warn("Dismissal: Keycloak account of {} stays enabled: {}", e.employeeId(), x.getMessage());
                    failed.add("costguard:" + login.email().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String email : found.addresses()) {
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

    /** Another employee (not erased) or an account not of this employee has the address, in any case. */
    private boolean sharedWithSomeoneElse(java.util.UUID employeeId, String address) {
        return jdbc.sql("""
                        select exists (select 1 from employee where id <> ? and erased_at is null
                                       and lower(email) = ?)
                            or exists (select 1 from user_account where employee_id is distinct from ?
                                       and lower(email) = ?)
                        """).params(employeeId, address, employeeId, address).query(Boolean.class).single();
    }

    /** The Keycloak account is the login of another CostGuard account: an address match must not close it. */
    private boolean linkedElsewhere(java.util.UUID tenantId, String accountId) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            session.bind(tenantId);
            return jdbc.sql("select exists (select 1 from user_account where keycloak_subject = ?)")
                    .param(accountId).query(Boolean.class).single();
        }));
    }
}
