package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Добавить сотрудника: почта в Microsoft 365, учётная запись с паролем, вход кодом (ADR 0059). */
@SpringBootTest(properties = {"costguard.registration.rate-limit.per-ip=4",
        "costguard.registration.rate-limit.per-email=3",
        "costguard.connections.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="})
@AutoConfigureMockMvc
@Import({RegistrationIntegrationTest.Fakes.class, OnboardingIntegrationTest.Graph.class})
class OnboardingIntegrationTest extends PostgresTestSupport {

    static final FakeIdentityProvider ACCOUNTS = RegistrationIntegrationTest.ACCOUNTS;
    static final FakeMicrosoftDirectory MICROSOFT = new FakeMicrosoftDirectory();

    @TestConfiguration
    static class Graph {
        @Bean
        @Primary
        FakeMicrosoftDirectory fakeMicrosoftDirectory() {
            return MICROSOFT;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String domain = "corp-" + UUID.randomUUID().toString().substring(0, 8) + ".example";

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "boss@" + domain)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private String department() throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/departments").with(as("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Продажи-%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void connect(boolean mailboxes) throws Exception {
        JdbcClient.create(superuserAppDataSource())
                .sql("insert into user_account (id, tenant_id, name, email, role) values (?, ?, 'x', ?, 'owner')")
                .params(UUID.randomUUID(), tenant, "boss@" + domain).update();
        mvc.perform(put("/api/v1/company/login").with(as("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"directoryId":"dir-1","clientId":"app-1","clientSecret":"s3cret","domains":["%s"],
                                 "createMailboxes":%s,"licenseSkuId":"c7df2760-2c81-4ef7-b578-5b5392b571df",
                                 "usageLocation":"ru"}
                                """.formatted(domain, mailboxes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createMailboxes").value(mailboxes))
                .andExpect(jsonPath("$.usageLocation").value("RU"));
    }

    private ResultActions add(String department, String email, boolean mailbox) throws Exception {
        return mvc.perform(post("/api/v1/employees/onboarding").with(as("owner"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Иван","lastName":"Щукин","departmentId":"%s","position":"Менеджер",
                         "email":%s,"createMailbox":%s}
                        """.formatted(department, email == null ? "null" : "\"" + email + "\"", mailbox)));
    }

    @Test
    void addingAnEmployeeMakesTheMailboxAndTheAccount() throws Exception {
        connect(true);
        String dept = department();
        String body = add(dept, null, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ivan.shchukin@" + domain))
                .andExpect(jsonPath("$.mailboxCreated").value(true))
                .andExpect(jsonPath("$.employee.name").value("Иван Щукин"))
                .andReturn().getResponse().getContentAsString();
        String password = JsonPath.read(body, "$.password");
        assertThat(password).hasSize(14).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");

        // Microsoft got the secret back from the sealed copy, and the same password
        assertThat(MICROSOFT.lastApp.clientSecret()).isEqualTo("s3cret");
        FakeMicrosoftDirectory.NewMailbox made = MICROSOFT.users.get("ivan.shchukin@" + domain);
        assertThat(made.password()).isEqualTo(password);
        assertThat(made.usageLocation()).isEqualTo("RU");
        FakeIdentityProvider.Stored account = ACCOUNTS.stored("ivan.shchukin@" + domain).orElseThrow();
        assertThat(account.role()).isEqualTo("employee");
        assertThat(account.tenantId()).isEqualTo(tenant);
        assertThat(ACCOUNTS.passwords.get(account.id())).isEqualTo(password);

        // the namesake gets the next free address
        add(dept, null, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ivan.shchukin2@" + domain));

        // the secret is not kept in the clear
        byte[] sealed = JdbcClient.create(superuserAppDataSource())
                .sql("select client_secret from company_login where tenant_id = ?").param(tenant)
                .query(byte[].class).single();
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("s3cret");
    }

    @Test
    void withoutMicrosoftTheAddressIsTyped() throws Exception {
        String dept = department();
        add(dept, null, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("mailboxes_off"));
        add(dept, null, false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("email_required"));
        String email = "petr-" + UUID.randomUUID() + "@" + domain;
        add(dept, email, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.mailboxCreated").value(false));
        assertThat(ACCOUNTS.stored(email)).isPresent();
    }

    @Test
    void aFailureTakesBackWhatWasMade() throws Exception {
        connect(true);
        String dept = department();
        int before = MICROSOFT.users.size();
        // an unknown department fails after the mailbox and the account were made
        add(UUID.randomUUID().toString(), null, true).andExpect(status().is4xxClientError());
        assertThat(MICROSOFT.users).hasSize(before);
        assertThat(ACCOUNTS.stored("ivan.shchukin@" + domain)).isEmpty();

        MICROSOFT.down = true;
        try {
            add(dept, null, true).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("mailbox_failed"));
        } finally {
            MICROSOFT.down = false;
        }
    }

    @Test
    void onlyThoseWhoManageTheOrgAdd() throws Exception {
        mvc.perform(post("/api/v1/employees/onboarding").with(as("finance")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName":"А","lastName":"Б","departmentId":"%s","position":"x","email":"a@b.example",
                         "createMailbox":false}
                        """.formatted(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @Test
    void aPersonChoosesTheCodeInsteadOfThePassword() throws Exception {
        String id = ACCOUNTS.existing("me-" + UUID.randomUUID() + "@" + domain, tenant);
        RequestPostProcessor me = jwt().jwt(j -> j.subject(id).claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("employee"))))
                .authorities(realmRoles);
        mvc.perform(get("/api/v1/me/sign-in").with(me)).andExpect(jsonPath("$.otp").value(false))
                .andExpect(jsonPath("$.setupUrl").value(org.hamcrest.Matchers.containsString("/account/")));
        ACCOUNTS.signIns.put(id, new IdentityProvider.SignIn(true, false));
        mvc.perform(put("/api/v1/me/sign-in/otp-first").with(me)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/sign-in").with(me)).andExpect(jsonPath("$.otpFirst").value(true));
    }

    @Test
    void aDismissalClosesTheAccountAndTheMicrosoftAccount() throws Exception {
        connect(true);
        String body = add(department(), null, true).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String email = JsonPath.read(body, "$.email");
        String employee = JsonPath.read(body, "$.employee.id");
        String account = ACCOUNTS.stored(email).orElseThrow().id();
        mvc.perform(put("/api/v1/employees/{id}/dismissal", employee).with(as("owner"))).andExpect(status().isOk());
        for (int i = 0; i < 100 && !(ACCOUNTS.disabled.contains(account) && MICROSOFT.disabled.contains(email)); i++) {
            Thread.sleep(100);
        }
        assertThat(ACCOUNTS.disabled).contains(account);
        assertThat(MICROSOFT.disabled).contains(email);
    }

    @Test
    void theMicrosoftSignInLogMarksLicensesUsed() throws Exception {
        connect(false);
        String dept = department();
        String body = add(dept, "anna-" + UUID.randomUUID() + "@" + domain, false).andReturn().getResponse()
                .getContentAsString();
        String email = JsonPath.read(body, "$.email");
        String vendor = JsonPath.read(mvc.perform(post("/api/v1/vendors").with(as("owner"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Figma-%s\",\"inn\":\"\",\"category\":\"SaaS\"}"
                        .formatted(UUID.randomUUID()))).andReturn().getResponse().getContentAsString(), "$.id");
        String subscription = JsonPath.read(mvc.perform(post("/api/v1/subscriptions").with(as("owner"))
                .contentType(MediaType.APPLICATION_JSON).content(("{\"vendorId\":\"%s\",\"product\":\"Figma Professional\","
                        + "\"contractId\":null,\"departmentId\":\"%s\",\"seats\":2,\"pricePerSeat\":1500,"
                        + "\"billingPeriod\":\"month\",\"renewalDate\":\"2027-01-01\"}").formatted(vendor, dept)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        RequestPostProcessor anna = jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("realm_access", Map.of("roles", List.of("employee"))))
                .authorities(realmRoles);
        mvc.perform(post("/api/v1/me/license-requests").with(anna).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subscriptionId\":\"%s\"}".formatted(subscription)))
                .andExpect(jsonPath("$.status").value("granted"));

        MICROSOFT.log.add(new MicrosoftDirectory.SignIn(email, "Figma", java.time.Instant.now()));
        MICROSOFT.log.add(new MicrosoftDirectory.SignIn(email, "Some Internal Tool", java.time.Instant.now()));
        mvc.perform(post("/api/v1/license-pool/microsoft-activity").with(as("it_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensesUpdated").value(1))
                .andExpect(jsonPath("$.unmatchedApps").value(org.hamcrest.Matchers.hasItem("Some Internal Tool")));
        java.time.LocalDate seen = JdbcClient.create(superuserAppDataSource())
                .sql("select last_active from license_assignment where subscription_id = ?::uuid").param(subscription)
                .query(java.time.LocalDate.class).single();
        assertThat(seen).isNotNull();
    }
}
