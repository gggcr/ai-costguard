package ai.costguard.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** «Синхронизировать сейчас» with Airflow configured: the core starts costguard_connector_sync (ADR 0019). */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionsAirflowIntegrationTest extends PostgresTestSupport {

    static final AirflowStub AIRFLOW = AirflowStub.start();

    @DynamicPropertySource
    static void airflow(DynamicPropertyRegistry registry) {
        registry.add("costguard.connections.airflow.url", AIRFLOW::url);
        registry.add("costguard.connections.airflow.username", () -> "airflow");
        registry.add("costguard.connections.airflow.password", () -> "secret");
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "a@x.ru")
                .claim("realm_access", Map.of("roles", List.of("it_admin")))).authorities(realmRoles);
    }

    private String connect() throws Exception {
        String response = mvc.perform(post("/api/v1/connections").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"okta","name":"Okta","settings":{"url":"https://romashka.okta.example"}}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void syncNowStartsTheAirflowDagForThatSystem() throws Exception {
        String id = connect();

        mvc.perform(post("/api/v1/connections/" + id + "/sync").with(admin()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("sync_requested"));

        AirflowStub.Call call = AIRFLOW.last();
        assertThat(call.path()).isEqualTo("/api/v1/dags/costguard_connector_sync/dagRuns");
        assertThat(call.authorization()).isEqualTo("Basic YWlyZmxvdzpzZWNyZXQ=");
        assertThat(call.body()).contains("\"dag_run_id\":\"manual-" + id + "-")
                .contains("\"connector\":\"okta\"").contains("\"tenant_id\":\"" + tenant + "\"")
                .contains("\"connection_id\":\"" + id + "\"");
    }

    @Test
    void airflowDownLeavesTheSyncForTheAgentsNextRound() throws Exception {
        String id = connect();
        AIRFLOW.answer(503);
        try {
            mvc.perform(post("/api/v1/connections/" + id + "/sync").with(admin()))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("sync_requested"));
        } finally {
            AIRFLOW.answer(200);
        }
    }
}
