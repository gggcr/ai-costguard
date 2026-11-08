package ai.costguard.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConnectionsApiIntegrationTest.Changes.class)
class ConnectionsApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class Changes {
        final List<ConnectionChanged> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ConnectionChanged event) {
            seen.add(event);
        }

        ConnectionChanged last(String id) {
            UUID connection = UUID.fromString(id);
            return seen.stream().filter(e -> e.connectionId().equals(connection)).reduce((a, b) -> b).orElseThrow();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Changes changes;
    @Autowired ConnectorRuns runs;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "a@x.ru")
                .claim("realm_access", Map.of("roles", List.of(roles)))).authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as(tenant, "it_admin");
    }

    private String connect(String body) throws Exception {
        String response = mvc.perform(post("/api/v1/connections").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static final String OKTA = """
            {"type":"okta","name":"Okta компании","settings":{"url":"https://romashka.okta.example"}}""";

    private void run(String type, UUID connection, boolean ok, int records, String error, Instant at) {
        runs.recorded(new ConnectorRuns.Run(UUID.randomUUID(), tenant, connection, type, at.minusSeconds(30), at, ok,
                records, error));
    }

    @Test
    void aConnectedSystemIsPendingUntilTheAgentPullsIt() throws Exception {
        String id = connect(OKTA);

        mvc.perform(get("/api/v1/connections").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("okta"))
                .andExpect(jsonPath("$[0].status").value("pending"))
                .andExpect(jsonPath("$[0].managedInCore").value(true))
                .andExpect(jsonPath("$[0].settings.url").value("https://romashka.okta.example"))
                .andExpect(jsonPath("$[0].lastSyncAt").value(nullValue()));
        ConnectionChanged sent = changes.last(id);
        assertThat(sent.enabled()).isTrue();
        assertThat(sent.tenantId()).isEqualTo(tenant);
        assertThat(sent.settings()).containsEntry("url", "https://romashka.okta.example");
    }

    @Test
    void pullsGiveTheStatusTheLastSyncTheRecordsAndTheLastError() throws Exception {
        String id = connect(OKTA);
        UUID connection = UUID.fromString(id);
        Instant t = Instant.parse("2026-11-08T10:00:00Z");

        run("okta", connection, true, 120, null, t);
        mvc.perform(get("/api/v1/connections/" + id).with(admin()))
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-11-08T10:00:00Z"))
                .andExpect(jsonPath("$.recordsLastSync").value(120));

        run("okta", connection, false, 0, "401 Unauthorized from Okta", t.plusSeconds(3600));
        mvc.perform(get("/api/v1/connections/" + id).with(admin()))
                .andExpect(jsonPath("$.status").value("failing"))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-11-08T10:00:00Z"))
                .andExpect(jsonPath("$.lastError").value("401 Unauthorized from Okta"))
                .andExpect(jsonPath("$.recordsTotal").value(120));
        mvc.perform(get("/api/v1/connections/" + id + "/runs").with(admin()))
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void aRunReportedTwiceCountsOnce() throws Exception {
        String id = connect(OKTA);
        var run = new ConnectorRuns.Run(UUID.randomUUID(), tenant, UUID.fromString(id), "okta",
                Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T10:01:00Z"), true, 50, null);
        runs.recorded(run);
        runs.recorded(run);
        mvc.perform(get("/api/v1/connections/" + id).with(admin())).andExpect(jsonPath("$.recordsTotal").value(50));
    }

    @Test
    void aSystemConfiguredOnlyInTheAgentsFileAppearsOnItsFirstPull() throws Exception {
        run("onec", null, true, 7, null, Instant.parse("2026-11-08T10:00:00Z"));
        run("nonsense", null, true, 7, null, Instant.parse("2026-11-08T10:00:00Z"));

        mvc.perform(get("/api/v1/connections").with(admin()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("onec"))
                .andExpect(jsonPath("$[0].managedInCore").value(false))
                .andExpect(jsonPath("$[0].status").value("connected"));
        // and it can be taken over by the core with its settings
        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"onec\",\"name\":\"1С Бухгалтерия\",\"settings\":"
                                + "{\"url\":\"http://1c.local/odata\",\"user\":\"costguard\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.managedInCore").value(true));
    }

    @Test
    void syncNowIsAskedOfTheAgentAndAnsweredByItsNextPull() throws Exception {
        String id = connect(OKTA);

        mvc.perform(post("/api/v1/connections/" + id + "/sync").with(admin()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("sync_requested"));
        ConnectionChanged asked = changes.last(id);
        assertThat(asked.syncRequestedAt()).isNotNull();

        run("okta", UUID.fromString(id), true, 3, null, Instant.now().plusSeconds(60));
        mvc.perform(get("/api/v1/connections/" + id).with(admin())).andExpect(jsonPath("$.status").value("connected"));
    }

    @Test
    void aDisconnectedSystemStopsAndCanBeConnectedAgain() throws Exception {
        String id = connect(OKTA);

        mvc.perform(delete("/api/v1/connections/" + id).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/connections/" + id).with(admin())).andExpect(status().isNoContent());
        assertThat(changes.last(id).enabled()).isFalse();
        mvc.perform(get("/api/v1/connections/" + id).with(admin()))
                .andExpect(jsonPath("$.status").value("disconnected"));
        mvc.perform(post("/api/v1/connections/" + id + "/sync").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_connected"));

        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON).content(OKTA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("pending"));
        assertThat(changes.last(id).enabled()).isTrue();
    }

    @Test
    void settingsAreCheckedAndNoSecretGetsIn() throws Exception {
        connect(OKTA);
        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON).content(OKTA))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_connected"));
        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"okta","name":"x","settings":{"url":"https://x","token":"00abc"}}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("setting_unknown"));
        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"entra","name":"x","settings":{"directory_id":"d"}}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("setting_missing"));
        mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"sap","name":"x","settings":{}}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_system_type"));
    }

    @Test
    void onlyTheOwnerAndItAdminChangeConnections() throws Exception {
        String id = connect(OKTA);
        for (String role : List.of("finance", "auditor", "agent_owner")) {
            mvc.perform(post("/api/v1/connections/" + id + "/sync").with(as(tenant, role)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
            mvc.perform(delete("/api/v1/connections/" + id).with(as(tenant, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/connections/" + id + "/sync").with(as(tenant, "owner")))
                .andExpect(status().isAccepted());
    }

    @Test
    void tenantsDoNotSeeEachOther() throws Exception {
        String id = connect(OKTA);
        UUID other = UUID.randomUUID();
        mvc.perform(get("/api/v1/connections").with(as(other, "owner"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/connections/" + id).with(as(other, "owner"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/connections/" + id + "/sync").with(as(other, "owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theTypesSayWhatToFillInAndWhatTheAgentNeeds() throws Exception {
        mvc.perform(get("/api/v1/connections/types").with(admin()))
                .andExpect(jsonPath("$[?(@.type == 'entra')].required[0]").value(hasItem("directory_id")))
                .andExpect(jsonPath("$[?(@.type == 'entra')].secrets[0]").value(hasItem("ENTRA_CLIENT_SECRET")));
    }
}
