package ai.costguard.core.org.internal;

import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Добавить сотрудника (ADR 0059): one step makes everything the person needs to start. With the company's Microsoft
 * 365 connected the work mailbox is made first (ivan.ivanov@company.ru); then a CostGuard account on that address
 * with a made-up password and the role employee; then the employee and their user. Something fails — what was made
 * is taken back.
 */
@Service
class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!#%+-=?@";

    private final OrgService orgs;
    private final OrgRepository org;
    private final CompanyLoginService logins;
    private final MicrosoftDirectory microsoft;
    private final IdentityProvider accounts;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    OnboardingService(OrgService orgs, OrgRepository org, CompanyLoginService logins, MicrosoftDirectory microsoft,
            IdentityProvider accounts, TenantSession session, TransactionTemplate transactions,
            ApplicationEventPublisher events) {
        this.orgs = orgs;
        this.org = org;
        this.logins = logins;
        this.microsoft = microsoft;
        this.accounts = accounts;
        this.session = session;
        this.transactions = transactions;
        this.events = events;
    }

    Onboarded add(UUID tenantId, NewEmployee request) {
        if (!accounts.configured()) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        String first = request.firstName().strip();
        String last = request.lastName().strip();
        Optional<CompanyLoginService.Mailboxes> mailboxes = transactions.execute(tx -> {
            session.bind(tenantId);
            return request.createMailbox() ? logins.mailboxes(tenantId) : Optional.empty();
        });
        if (request.createMailbox() && mailboxes.isEmpty()) {
            throw CompanyLoginProblem.MAILBOXES_OFF.failure();
        }
        if (!request.createMailbox() && (request.email() == null || request.email().isBlank())) {
            throw CompanyLoginProblem.EMAIL_REQUIRED.failure();
        }
        String password = password();

        MicrosoftDirectory.Mailbox mailbox = null;
        String email;
        if (mailboxes.isPresent()) {
            CompanyLoginService.Mailboxes m = mailboxes.get();
            try {
                mailbox = microsoft.create(m.app(), new MicrosoftDirectory.NewMailbox(nickname(first, last),
                        m.domain(), first, last, password, m.licenseSku(), m.usageLocation()));
            } catch (MicrosoftDirectory.Unavailable e) {
                log.warn("Mailbox failed: {}", e.getMessage());
                throw CompanyLoginProblem.MAILBOX_FAILED.failure();
            }
            email = mailbox.address().toLowerCase(Locale.ROOT);
        } else {
            email = RegistrationService.email(request.email());
        }

        String accountId = null;
        try {
            try {
                accountId = accounts.createWithPassword(new IdentityProvider.NewAccount(email, first, last, tenantId,
                        UserRole.employee.name()), password).orElseThrow(OrgProblem.EMAIL_TAKEN::failure);
            } catch (IdentityProvider.Unavailable e) {
                log.warn("Account failed: {}", e.getMessage());
                throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
            }
            final String account = accountId;
            final boolean made = mailbox != null;
            Employee employee = transactions.execute(tx -> {
                session.bind(tenantId);
                Employee created = orgs.createEmployee(tenantId, new EmployeeDetails(first + " " + last, email,
                        request.departmentId(), request.position().strip()));
                org.insertUser(tenantId, UUID.randomUUID(), new NewUser(first + " " + last, email,
                        UserRole.employee, created.id()), account);
                events.publishEvent(ActionTaken.of(tenantId, "employee.onboarded", "employee", created.id(),
                        Map.of("mailbox", made, "account", account)));
                return created;
            });
            return new Onboarded(employee, email, password, made);
        } catch (RuntimeException e) {
            if (accountId != null) {
                try {
                    accounts.delete(accountId);
                } catch (IdentityProvider.Unavailable left) {
                    log.warn("An account of a failed onboarding was left in Keycloak: {}", left.getMessage());
                }
            }
            if (mailbox != null) {
                try {
                    microsoft.delete(mailboxes.get().app(), mailbox.id());
                } catch (MicrosoftDirectory.Unavailable left) {
                    log.warn("A mailbox of a failed onboarding was left in Microsoft 365: {}", mailbox.address());
                }
            }
            throw e;
        }
    }

    /** Иван Иванов → ivan.ivanov */
    static String nickname(String first, String last) {
        String nick = (latin(first) + "." + latin(last)).replaceAll("[^a-z0-9.]", "").replaceAll("\\.+", ".")
                .replaceAll("^\\.|\\.$", "");
        return nick.isEmpty() ? "user" : nick;
    }

    private static final String[][] RU = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"},
            {"ё", "e"}, {"ж", "zh"}, {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"},
            {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"},
            {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""},
            {"э", "e"}, {"ю", "yu"}, {"я", "ya"}, {" ", "."}, {"-", "-"}};

    private static String latin(String name) {
        String s = name.strip().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            String mapped = null;
            for (String[] pair : RU) {
                if (pair[0].charAt(0) == c) {
                    mapped = pair[1];
                    break;
                }
            }
            out.append(mapped != null ? mapped : String.valueOf(c));
        }
        return Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** 14 characters with all four kinds Microsoft asks for, no look-alikes (0/O, 1/l). */
    String password() {
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        char[] p = new char[14];
        p[0] = UPPER.charAt(random.nextInt(UPPER.length()));
        p[1] = LOWER.charAt(random.nextInt(LOWER.length()));
        p[2] = DIGITS.charAt(random.nextInt(DIGITS.length()));
        p[3] = SYMBOLS.charAt(random.nextInt(SYMBOLS.length()));
        for (int i = 4; i < p.length; i++) {
            p[i] = all.charAt(random.nextInt(all.length()));
        }
        for (int i = p.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        return new String(p);
    }
}
