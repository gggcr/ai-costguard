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
    private final LoginSecret secrets;

    CompanyLoginService(JdbcClient jdbc, IdentityProvider identity, OrgRepository org, TenantSession session,
            ApplicationEventPublisher events, Clock clock, LoginSecret secrets,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.secrets = secrets;
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
        return jdbc.sql("""
                        select provider, directory_id, client_id, domains, create_mailboxes, license_sku,
                               usage_location from company_login""")
                .query((rs, i) -> new CompanyLogin(true, rs.getString("provider"), rs.getString("directory_id"),
                        rs.getString("client_id"), split(rs.getString("domains")), redirectUri(tenantId),
                        rs.getBoolean("create_mailboxes"), rs.getString("license_sku"),
                        rs.getString("usage_location")))
                .optional()
                .orElse(new CompanyLogin(false, "microsoft", null, null, List.of(), redirectUri(tenantId), false,
                        null, null));
    }

    @Transactional
    CompanyLogin save(UUID tenantId, CompanyLoginSettings s) {
        session.bind(tenantId);
        if (!identity.configured()) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        boolean first = jdbc.sql("select count(*) from company_login").query(Integer.class).single() == 0;
        boolean newSecret = s.clientSecret() != null && !s.clientSecret().isBlank();
        if (first && !newSecret) {
            throw CompanyLoginProblem.SECRET_REQUIRED.failure();
        }
        boolean mailboxes = Boolean.TRUE.equals(s.createMailboxes());
        boolean secretKept = !first && jdbc.sql("select client_secret is not null from company_login")
                .query(Boolean.class).single();
        if (mailboxes && !newSecret && !secretKept) {
            // a login set up before mailboxes kept its secret only in Keycloak: the core needs it once more
            throw CompanyLoginProblem.SECRET_REQUIRED.failure();
        }
        if (mailboxes && !secrets.available()) {
            throw CompanyLoginProblem.ENCRYPTION_KEY_MISSING.failure();
        }
        // the core keeps the secret only when it can seal it; login alone needs it in Keycloak only
        boolean keep = newSecret && secrets.available();
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
                insert into company_login (tenant_id, provider, directory_id, client_id, alias, domains, updated_at,
                    client_secret, create_mailboxes, license_sku, usage_location)
                values (?, 'microsoft', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set directory_id = excluded.directory_id,
                    client_id = excluded.client_id, domains = excluded.domains, updated_at = excluded.updated_at,
                    client_secret = coalesce(excluded.client_secret, company_login.client_secret),
                    create_mailboxes = excluded.create_mailboxes, license_sku = excluded.license_sku,
                    usage_location = excluded.usage_location""")
                .params(tenantId, s.directoryId().strip(), s.clientId().strip(), alias(tenantId),
                        String.join(",", domains), Timestamp.from(clock.instant()),
                        keep ? secrets.seal(tenantId, s.clientSecret().strip()) : null, mailboxes,
                        blank(s.licenseSkuId()), s.usageLocation() == null || s.usageLocation().isBlank() ? null
                                : s.usageLocation().toUpperCase(Locale.ROOT))
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

    /** What adding an employee needs to make a mailbox; empty when the company has not turned it on. */
    record Mailboxes(MicrosoftDirectory.App app, String domain, String licenseSku, String usageLocation) {
    }

    /** Call with the tenant bound. */
    Optional<Mailboxes> mailboxes(UUID tenantId) {
        return jdbc.sql("""
                        select directory_id, client_id, client_secret, domains, license_sku, usage_location
                        from company_login where create_mailboxes and client_secret is not null""")
                .query((rs, i) -> new Mailboxes(new MicrosoftDirectory.App(rs.getString("directory_id"),
                        rs.getString("client_id"), secrets.open(tenantId, rs.getBytes("client_secret"))),
                        split(rs.getString("domains")).get(0), rs.getString("license_sku"),
                        rs.getString("usage_location")))
                .optional();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static List<String> split(String domains) {
        return Arrays.stream(domains.split(",")).filter(d -> !d.isBlank()).toList();
    }
}
