package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Self-registration through the API, with Keycloak in memory (ADR 0036): what is created, what the answer does not
 * reveal, the rate limit, activation at the first login and the sweeper. Everything runs as the application role,
 * so RLS applies.
 */
@SpringBootTest(properties = {"costguard.registration.rate-limit.per-ip=4",
        "costguard.registration.rate-limit.per-email=3"})
@AutoConfigureMockMvc
@Import(RegistrationIntegrationTest.Fakes.class)
class RegistrationIntegrationTest extends PostgresTestSupport {

    static final FakeIdentityProvider ACCOUNTS = new FakeIdentityProvider();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeIdentityProvider fakeIdentityProvider() {
            return ACCOUNTS;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired RegistrationService registrations;

    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());

    @AfterEach
    void reset() {
        ACCOUNTS.configured = true;
        ACCOUNTS.failEmails = false;
    }

    private static String address() {
        return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (int) (Math.random()
                * 250);
    }

    private static String email() {
        return "owner-" + UUID.randomUUID() + "@acme.example";
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String body(String email, String country, String currency) {
        return """
                {"companyName":"ООО «Альфа»","country":"%s","currency":"%s","email":"%s",
                 "ownerFirstName":"Анна","ownerLastName":"Петрова"}
                """.formatted(country, currency, email);
    }

    private ResultActions register(String email, String address) throws Exception {
        return mvc.perform(post("/api/v1/registrations").with(from(address)).contentType(MediaType.APPLICATION_JSON)
                .content(body(email, "RU", "RUB")));
    }

    private UUID tenantOf(String email) {
        return ACCOUNTS.stored(email).orElseThrow().tenantId();
    }

    private RequestPostProcessor loggedIn(UUID tenant, String email, String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("email_verified", true).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private String tenantStatus(UUID tenant) {
        return admin.sql("select status from tenant where id = ?").param(tenant).query(String.class).optional()
                .orElse(null);
    }

    @Test
    void createsAPendingCompanyItsOwnerAndATrial() throws Exception {
        String email = email();

        register(email, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("check_email"));

        FakeIdentityProvider.Stored account = ACCOUNTS.stored(email).orElseThrow();
        assertThat(account.role()).isEqualTo("owner");
        assertThat(account.firstName()).isEqualTo("Анна");
        assertThat(account.lastName()).isEqualTo("Петрова");
        assertThat(account.emailVerified()).isFalse();
        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(1);
        UUID tenant = account.tenantId();
        assertThat(admin.sql("select name || '|' || country || '|' || currency || '|' || status from tenant where id = ?")
                .param(tenant).query(String.class).single()).isEqualTo("ООО «Альфа»|RU|RUB|pending");
        assertThat(admin.sql("select role from user_account where tenant_id = ? and email = ?").params(tenant, email)
                .query(String.class).single()).isEqualTo("owner");
        assertThat(admin.sql("select keycloak_user_id from registration where tenant_id = ?").param(tenant)
                .query(String.class).single()).isEqualTo(account.id());
        Instant until = admin.sql("select access_until from tenant_plan where tenant_id = ? and plan = 'trial'")
                .param(tenant).query(Timestamp.class).single().toInstant();
        assertThat(until).isBetween(Instant.now().plus(Duration.ofDays(13)), Instant.now().plus(Duration.ofDays(15)));
    }

    @Test
    void theAnswerDoesNotTellWhoIsRegistered() throws Exception {
        String email = email();
        register(email, address()).andExpect(status().isAccepted());

        // Pending: the same answer, and the link is sent again; no second company.
        register(email, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("check_email"));
        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(2);
        assertThat(admin.sql("select count(*) from user_account where email = ?").param(email).query(Long.class)
                .single()).isEqualTo(1);

        // Confirmed: the same answer, nothing sent.
        ACCOUNTS.confirm(email);
        register(email, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("check_email"));
        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(2);

        // Another company's user: the same answer, nothing sent, nothing created.
        String taken = email();
        ACCOUNTS.existing(taken, UUID.randomUUID());
        register(taken, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("check_email"));
        assertThat(ACCOUNTS.emailsTo(taken)).isZero();
        assertThat(admin.sql("select count(*) from user_account where email = ?").param(taken).query(Long.class)
                .single()).isZero();
    }

    @Test
    void countryAndCurrencyMustBeRealCodes() throws Exception {
        mvc.perform(post("/api/v1/registrations").with(from(address())).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), "XX", "RUB")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("country_unknown"));
        mvc.perform(post("/api/v1/registrations").with(from(address())).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), "RU", "XYZ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("currency_unknown"));
        mvc.perform(post("/api/v1/registrations").with(from(address())).contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "RU", "RUB")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attemptsAreLimitedPerAddress() throws Exception {
        String address = address();
        for (int i = 0; i < 4; i++) {
            register(email(), address).andExpect(status().isAccepted());
        }

        register(email(), address).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_registrations"));
        register(email(), address()).andExpect(status().isAccepted());
    }

    @Test
    void attemptsAreLimitedPerEmail() throws Exception {
        String email = email();
        for (int i = 0; i < 3; i++) {
            register(email, address()).andExpect(status().isAccepted());
        }

        register(email, address()).andExpect(status().isTooManyRequests());
        assertThat(admin.sql("select count(*) from registration_attempt where key_hash = ?")
                .param(RegistrationAttempts.hash(email)).query(Long.class).single()).isEqualTo(4);
        assertThat(admin.sql("select count(*) from registration_attempt where key_hash = ?").param(email)
                .query(Long.class).single()).as("only hashes are stored").isZero();
    }

    @Test
    void theFirstLoginActivatesTheCompany() throws Exception {
        String email = email();
        register(email, address()).andExpect(status().isAccepted());
        UUID tenant = tenantOf(email);
        ACCOUNTS.confirm(email);

        mvc.perform(get("/api/v1/billing/plan").with(loggedIn(tenant, email, "owner"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("trial")).andExpect(jsonPath("$.status").value("trial"));

        assertThat(tenantStatus(tenant)).isEqualTo("active");
        assertThat(admin.sql("select count(*) from registration_timer where tenant_id = ?").param(tenant)
                .query(Long.class).single()).isZero();
        assertThat(admin.sql("select count(*) from registration where tenant_id = ? and confirmed_at is not null")
                .param(tenant).query(Long.class).single()).isEqualTo(1);
        assertThat(admin.sql("select count(*) from activity_entry where tenant_id = ? and action = 'company.activated'")
                .param(tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theSweeperDropsWhatNobodyConfirmedAndActivatesWhatWasConfirmed() throws Exception {
        String dropped = email();
        String confirmed = email();
        register(dropped, address()).andExpect(status().isAccepted());
        register(confirmed, address()).andExpect(status().isAccepted());
        UUID droppedTenant = tenantOf(dropped);
        UUID confirmedTenant = tenantOf(confirmed);
        ACCOUNTS.confirm(confirmed);
        admin.sql("update registration_timer set expires_at = now() - interval '1 minute' where tenant_id in (?, ?)")
                .params(droppedTenant, confirmedTenant).update();

        assertThat(registrations.expired()).contains(droppedTenant, confirmedTenant);
        registrations.sweep(droppedTenant);
        registrations.sweep(confirmedTenant);

        assertThat(tenantStatus(droppedTenant)).isNull();
        assertThat(ACCOUNTS.stored(dropped)).isEmpty();
        for (String table : List.of("user_account", "registration", "registration_timer", "tenant_plan",
                "plan_schedule", "activity_entry")) {
            assertThat(admin.sql("select count(*) from " + table + " where tenant_id = ?").param(droppedTenant)
                    .query(Long.class).single()).as(table).isZero();
        }
        assertThat(tenantStatus(confirmedTenant)).isEqualTo("active");
        assertThat(ACCOUNTS.stored(confirmed)).isPresent();

        // The e-mail of the dropped registration can register again.
        register(dropped, address()).andExpect(status().isAccepted());
        assertThat(ACCOUNTS.stored(dropped)).isPresent();
    }

    @Test
    void registrationIsOffWithoutTheKeycloakClient() throws Exception {
        ACCOUNTS.configured = false;

        register(email(), address()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("registration_unavailable"));
    }

    @Test
    void aFailedEmailSaysTryAgainAndTheRetrySendsIt() throws Exception {
        String email = email();
        ACCOUNTS.failEmails = true;
        register(email, address()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("setup_email_failed"));

        ACCOUNTS.failEmails = false;
        register(email, address()).andExpect(status().isAccepted());
        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(1);
    }
}
