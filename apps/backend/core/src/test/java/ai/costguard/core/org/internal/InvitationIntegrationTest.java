package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** An owner invites people with roles (ADR 0036), with Keycloak in memory. Shares the registration test's context. */
@SpringBootTest(properties = {"costguard.registration.rate-limit.per-ip=4",
        "costguard.registration.rate-limit.per-email=3"})
@AutoConfigureMockMvc
@Import(RegistrationIntegrationTest.Fakes.class)
class InvitationIntegrationTest extends PostgresTestSupport {

    static final FakeIdentityProvider ACCOUNTS = RegistrationIntegrationTest.ACCOUNTS;

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "boss@acme.example")
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    /** The invitee's token: its sub is the id of the Keycloak account the invitation created, as Keycloak issues it. */
    private RequestPostProcessor invitee(String email, String role) {
        String sub = ACCOUNTS.stored(email).orElseThrow().id();
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("email_verified", true).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private static String email() {
        return "person-" + UUID.randomUUID() + "@acme.example";
    }

    private ResultActions invite(String email, String role, String by) throws Exception {
        return mvc.perform(post("/api/v1/invitations").with(as(by)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","firstName":"Иван","lastName":"Иванов","role":"%s"}
                        """.formatted(email, role)));
    }

    private String invited(String email, String role) throws Exception {
        return JsonPath.read(invite(email, role, "owner").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }

    @Test
    void anOwnerInvitesAPersonWithARole() throws Exception {
        String email = email();

        invite(email, "finance", "owner").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("sent")).andExpect(jsonPath("$.role").value("finance"));

        FakeIdentityProvider.Stored account = ACCOUNTS.stored(email).orElseThrow();
        assertThat(account.tenantId()).isEqualTo(tenant);
        assertThat(account.role()).isEqualTo("finance");
        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(1);
        mvc.perform(get("/api/v1/users").with(as("owner")))
                .andExpect(jsonPath("$[?(@.email == '%s')].role".formatted(email)).value("finance"));
        mvc.perform(get("/api/v1/invitations").with(as("owner")))
                .andExpect(jsonPath("$[?(@.email == '%s')].status".formatted(email)).value("sent"));
    }

    @Test
    void onlyAnOwnerInvites() throws Exception {
        String email = email();

        invite(email, "auditor", "it_admin").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("owner_required"));
        assertThat(ACCOUNTS.stored(email)).isEmpty();
    }

    @Test
    void anEmailIsInvitedOnceAndNeverFromAnotherCompany() throws Exception {
        String email = email();
        invited(email, "finance");
        invite(email, "auditor", "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));

        String elsewhere = email();
        ACCOUNTS.existing(elsewhere, UUID.randomUUID());
        invite(elsewhere, "auditor", "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("account_exists"));
        mvc.perform(get("/api/v1/users").with(as("owner")))
                .andExpect(jsonPath("$[?(@.email == '%s')]".formatted(elsewhere)).isEmpty());
    }

    @Test
    void theFirstLoginAcceptsAndThenItCannotBeTakenBack() throws Exception {
        String email = email();
        String id = invited(email, "agent_owner");
        ACCOUNTS.confirm(email);

        mvc.perform(get("/api/v1/departments").with(invitee(email, "agent_owner"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/invitations").with(as("owner")))
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(id)).value("accepted"));
        mvc.perform(delete("/api/v1/invitations/" + id).with(as("owner"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invitation_accepted"));
        mvc.perform(post("/api/v1/invitations/" + id + "/resend").with(as("owner")))
                .andExpect(status().isConflict());
    }

    /**
     * The e-mail spoofing finding of 2026-11-12: a confirmed token with the invitee's address, but of another Keycloak
     * account, does not accept the invitation nor take the invited account.
     */
    @Test
    void anotherKeycloakAccountWithTheInviteesEmailAcceptsNothing() throws Exception {
        String email = email();
        String id = invited(email, "finance");
        ACCOUNTS.confirm(email);
        RequestPostProcessor other = jwt().jwt(j -> j.subject("kc-other-" + UUID.randomUUID())
                        .claim("tenant_id", tenant.toString()).claim("email", email).claim("email_verified", true)
                        .claim("realm_access", Map.of("roles", List.of("finance"))))
                .authorities(realmRoles);

        mvc.perform(get("/api/v1/departments").with(other)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/invitations").with(as("owner")))
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(id)).value("sent"));
        mvc.perform(get("/api/v1/notifications/settings").with(other)).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/departments").with(invitee(email, "finance"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/invitations").with(as("owner")))
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(id)).value("accepted"));
        mvc.perform(get("/api/v1/notifications/settings").with(invitee(email, "finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void theLinkCanBeSentAgainWhileWaiting() throws Exception {
        String email = email();
        String id = invited(email, "auditor");

        mvc.perform(post("/api/v1/invitations/" + id + "/resend").with(as("owner"))).andExpect(status().isAccepted());

        assertThat(ACCOUNTS.emailsTo(email)).isEqualTo(2);
    }

    @Test
    void aRevokedInvitationTakesTheAccountAndTheUser() throws Exception {
        String email = email();
        String id = invited(email, "auditor");

        mvc.perform(delete("/api/v1/invitations/" + id).with(as("owner"))).andExpect(status().isNoContent());

        assertThat(ACCOUNTS.stored(email)).isEmpty();
        mvc.perform(get("/api/v1/users").with(as("owner")))
                .andExpect(jsonPath("$[?(@.email == '%s')]".formatted(email)).isEmpty());
        mvc.perform(get("/api/v1/invitations").with(as("owner")))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).isEmpty());
    }

    @Test
    void closingTheAccessOfAnInvitedUserDeletesTheAccount() throws Exception {
        String email = email();
        String invitation = invited(email, "finance");
        String user = JsonPath.<List<String>>read(mvc.perform(get("/api/v1/users").with(as("owner"))).andReturn()
                .getResponse().getContentAsString(), "$[?(@.email == '%s')].id".formatted(email)).getFirst();
        assertThat(invitation).isNotBlank();

        mvc.perform(delete("/api/v1/users/" + user).with(as("owner"))).andExpect(status().isNoContent());

        assertThat(ACCOUNTS.stored(email)).isEmpty();
    }
}
