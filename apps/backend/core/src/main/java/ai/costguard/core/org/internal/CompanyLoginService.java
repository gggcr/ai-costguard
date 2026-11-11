package ai.costguard.core.org.internal;

import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Вход через Microsoft 365 (ADR 0058): the owner connects the company's Entra ID once; the core makes the provider
 * in Keycloak and remembers which e-mail domains lead to it. People who have an account log in with their work
 * account and a one-time code of an authenticator app.
 */
@Service
class CompanyLoginService {

    private final JdbcClient jdbc;
    private final IdentityProvider identity;
    private final OrgRepository org;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String issuer;

    CompanyLoginService(JdbcClient jdbc, IdentityProvider identity, OrgRepository org, TenantSession session,
            ApplicationEventPublisher events, Clock clock,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.org = org;
        this.session = session;
        this.events = events;
        this.clock = clock;
        this.issuer = issuer.replaceAll("/+$", "");
    }

    static String alias(UUID tenantId) {
        return "ms-" + tenantId;
    }

    private String redirectUri(UUID tenantId) {
        return issuer + "/broker/" + alias(tenantId) + "/endpoint";
    }

    @Transactional(readOnly = true)
    CompanyLogin get(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("select provider, directory_id, client_id, domains from company_login")
                .query((rs, i) -> new CompanyLogin(true, rs.getString("provider"), rs.getString("directory_id"),
                        rs.getString("client_id"), split(rs.getString("domains")), redirectUri(tenantId)))
                .optional()
                .orElse(new CompanyLogin(false, "microsoft", null, null, List.of(), redirectUri(tenantId)));
    }

    @Transactional
    CompanyLogin save(UUID tenantId, CompanyLoginSettings s) {
        session.bind(tenantId);
        if (!identity.configured()) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        boolean first = jdbc.sql("select count(*) from company_login").query(Integer.class).single() == 0;
        if (first && (s.clientSecret() == null || s.clientSecret().isBlank())) {
            throw CompanyLoginProblem.SECRET_REQUIRED.failure();
        }
        List<String> domains = s.domains().stream().map(d -> d.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        for (String d : domains) {
            Optional<UUID> owner = jdbc.sql("select tenant_id from login_domain where domain = ?").param(d)
                    .query(UUID.class).optional();
            if (owner.isPresent() && !owner.get().equals(tenantId)) {
                throw CompanyLoginProblem.DOMAIN_TAKEN.failure();
            }
            // a company names only domains its own people use: no claiming gmail.com or another company's domain
            int ours = jdbc.sql("select count(*) from user_account where lower(email) like ?").param("%@" + d)
                    .query(Integer.class).single();
            if (ours == 0) {
                throw CompanyLoginProblem.DOMAIN_NOT_OURS.failure();
            }
        }
        String name = org.company().map(Company::name).orElse("компании");
        try {
            identity.saveMicrosoftLogin(new IdentityProvider.MicrosoftLogin(alias(tenantId), tenantId,
                    s.directoryId().strip(), s.clientId().strip(), s.clientSecret(), "Microsoft 365 · " + name));
        } catch (IdentityProvider.Unavailable e) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        jdbc.sql("""
                insert into company_login (tenant_id, provider, directory_id, client_id, alias, domains, updated_at)
                values (?, 'microsoft', ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set directory_id = excluded.directory_id,
                    client_id = excluded.client_id, domains = excluded.domains, updated_at = excluded.updated_at""")
                .params(tenantId, s.directoryId().strip(), s.clientId().strip(), alias(tenantId),
                        String.join(",", domains), Timestamp.from(clock.instant()))
                .update();
        jdbc.sql("delete from login_domain where tenant_id = ?").param(tenantId).update();
        for (String d : domains) {
            jdbc.sql("insert into login_domain (domain, tenant_id, alias) values (?, ?, ?)")
                    .params(d, tenantId, alias(tenantId)).update();
        }
        events.publishEvent(ActionTaken.of(tenantId, "login.microsoft.saved", "company_login", tenantId,
                ActionTaken.details("domains", String.join(",", domains))));
        return get(tenantId);
    }

    @Transactional
    void remove(UUID tenantId) {
        session.bind(tenantId);
        try {
            identity.removeLogin(alias(tenantId));
        } catch (IdentityProvider.Unavailable e) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        jdbc.sql("delete from company_login").update();
        jdbc.sql("delete from login_domain where tenant_id = ?").param(tenantId).update();
        events.publishEvent(ActionTaken.of(tenantId, "login.microsoft.removed", "company_login", tenantId,
                java.util.Map.of()));
    }

    /** Before anyone is signed in: the provider of the domain of this e-mail, if a company connected one. */
    Optional<LoginHint> hint(String email) {
        if (email == null || !email.contains("@")) {
            return Optional.empty();
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).strip().toLowerCase(Locale.ROOT);
        return jdbc.sql("select alias from login_domain where domain = ?").param(domain).query(String.class)
                .optional().map(a -> new LoginHint("microsoft", a));
    }

    private static List<String> split(String domains) {
        return Arrays.stream(domains.split(",")).filter(d -> !d.isBlank()).toList();
    }
}
