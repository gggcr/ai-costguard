package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.crypto.Sealer;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Доступ у поставщика (ADR 0065): a seat follows in Microsoft Entra or through the vendor's SCIM API. */
@SpringBootTest(properties = {"costguard.registration.rate-limit.per-ip=4",
        "costguard.registration.rate-limit.per-email=3",
        // the test SCIM server listens on 127.0.0.1 (ScimClientTest checks the guard itself)
        "costguard.access.scim-private-addresses=true",
        "costguard.connections.encryption-key=" + VendorAccessIntegrationTest.KEY})
@AutoConfigureMockMvc
@Import({RegistrationIntegrationTest.Fakes.class, OnboardingIntegrationTest.Graph.class})
class VendorAccessIntegrationTest extends PostgresTestSupport {

    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final FakeMicrosoftDirectory MICROSOFT = OnboardingIntegrationTest.MICROSOFT;

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String domain = "corp-" + UUID.randomUUID().toString().substring(0, 8) + ".example";

    private RequestPostProcessor as(String role, String email) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor it() {
        return as("it_admin", "it@" + domain);
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(post(path).with(as("owner", "boss@" + domain))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String department() throws Exception {
        return create("/api/v1/departments", "{\"name\":\"D-%s\"}".formatted(UUID.randomUUID()));
    }

    private String subscription(String dept, String product) throws Exception {
        String vendor = create("/api/v1/vendors", "{\"name\":\"V-%s\",\"inn\":\"\",\"category\":\"SaaS\"}"
                .formatted(UUID.randomUUID()));
        return create("/api/v1/subscriptions", ("{\"vendorId\":\"%s\",\"product\":\"%s\",\"contractId\":null,"
                + "\"departmentId\":\"%s\",\"seats\":1,\"pricePerSeat\":1500,\"billingPeriod\":\"month\","
                + "\"renewalDate\":\"2027-01-01\"}").formatted(vendor, product, dept));
    }

    private String person(String dept) throws Exception {
        String email = "p-" + UUID.randomUUID() + "@" + domain;
        String id = create("/api/v1/employees", "{\"name\":\"Анна Петрова\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, dept));
        // the CostGuard account an onboarding or an invitation leaves, linked to the person's Keycloak sub
        login(tenant, email, "employee", UUID.fromString(id));
        return email;
    }

    private void ask(String email, String subscription) throws Exception {
        mvc.perform(post("/api/v1/me/license-requests").with(as("employee", email))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"%s\"}".formatted(subscription)))
                .andExpect(jsonPath("$.status").value("granted"));
    }

    private void idleSince90Days(String subscription) {
        JdbcClient.create(superuserAppDataSource())
                .sql("update license_assignment set last_active = current_date - 90 where subscription_id = ?::uuid")
                .param(subscription).update();
    }

    private static void waitFor(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }

    private void connectMicrosoft() throws Exception {
        JdbcClient.create(superuserAppDataSource())
                .sql("insert into user_account (id, tenant_id, name, email, role) values (?, ?, 'x', ?, 'owner')")
                .params(UUID.randomUUID(), tenant, "boss@" + domain).update();
        mvc.perform(put("/api/v1/company/login").with(as("owner", "boss@" + domain))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"directoryId\":\"dir-1\",\"clientId\":\"app-1\",\"clientSecret\":\"s\","
                                + "\"domains\":[\"" + domain + "\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void aSeatFollowsInMicrosoftEntra() throws Exception {
        connectMicrosoft();
        String dept = department();
        String figma = subscription(dept, "Figma Org");
        MICROSOFT.apps.add(new MicrosoftDirectory.EnterpriseApp("sp-figma", "Figma"));
        mvc.perform(get("/api/v1/microsoft/apps").with(it()))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("Figma")));
        mvc.perform(put("/api/v1/license-pool/{id}/access", figma).with(it()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"entra\",\"entraAppId\":\"sp-figma\",\"entraAppName\":\"Figma\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("entra"));

        String a = person(dept);
        ask(a, figma);
        waitFor(() -> MICROSOFT.assigned.contains("sp-figma|" + a));
        assertThat(MICROSOFT.assigned).contains("sp-figma|" + a);

        // idle: taking it back removes the assignment, and Entra removes the person at Figma
        idleSince90Days(figma);
        mvc.perform(post("/api/v1/license-pool/reclaim").with(it())).andExpect(status().isOk());
        waitFor(() -> !MICROSOFT.assigned.contains("sp-figma|" + a));
        assertThat(MICROSOFT.assigned).doesNotContain("sp-figma|" + a);
        waitFor(() -> {
            try {
                return JsonPath.<Integer>read(mvc.perform(get("/api/v1/license-pool/{id}/access", figma).with(it()))
                        .andReturn().getResponse().getContentAsString(), "$.recent.length()") >= 2;
            } catch (Exception e) {
                return false;
            }
        });
        mvc.perform(get("/api/v1/license-pool/{id}/access", figma).with(it()))
                .andExpect(jsonPath("$.recent[0].action").value("revoke"))
                .andExpect(jsonPath("$.recent[0].status").value("done"))
                .andExpect(jsonPath("$.recent[0].employeeName").value("Анна Петрова"));
    }

    @Test
    void aSeatFollowsThroughTheVendorsScim() throws Exception {
        Map<String, String> users = new ConcurrentHashMap<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scim/v2/Users", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            String answer = "{}";
            int code = 200;
            if ("GET".equals(ex.getRequestMethod())) {
                String q = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                String email = q.substring(q.indexOf('"') + 1, q.lastIndexOf('"'));
                answer = users.containsKey(email)
                        ? "{\"Resources\":[{\"id\":\"" + email + "\"}]}" : "{\"Resources\":[]}";
            } else if ("POST".equals(ex.getRequestMethod())) {
                users.put(JsonPath.read(body, "$.userName"), "active");
                code = 201;
            } else if ("PATCH".equals(ex.getRequestMethod())) {
                users.put(path.substring(path.lastIndexOf('/') + 1), body.contains("false") ? "inactive" : "active");
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String dept = department();
            String miro = subscription(dept, "Miro Business");
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            // only https addresses are accepted through the API
            mvc.perform(put("/api/v1/license-pool/{id}/access", miro).with(it()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"scim\",\"scimUrl\":\"" + base + "\",\"scimToken\":\"t0ken\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/v1/license-pool/{id}/access", miro).with(it()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"scim\",\"scimUrl\":\"https://scim.example/v2\"}"))
                    .andExpect(status().isBadRequest());
            // the test server speaks http: its row is written as the API would, with the token sealed
            JdbcClient.create(superuserAppDataSource()).sql("""
                    insert into subscription_access (tenant_id, subscription_id, kind, scim_url, scim_token, updated_at)
                    values (?, ?::uuid, 'scim', ?, ?, now())""")
                    .params(tenant, miro, base, new Sealer(KEY).seal(tenant + ":scim:" + miro, "t0ken")).update();
            mvc.perform(get("/api/v1/license-pool/{id}/access", miro).with(it()))
                    .andExpect(jsonPath("$.scimTokenSet").value(true))
                    .andExpect(jsonPath("$.scimToken").doesNotExist());

            String b = person(dept);
            ask(b, miro);
            waitFor(() -> "active".equals(users.get(b)));
            assertThat(users).containsEntry(b, "active");
            assertThat(auth.get()).isEqualTo("Bearer t0ken");

            idleSince90Days(miro);
            mvc.perform(post("/api/v1/license-pool/reclaim").with(it())).andExpect(status().isOk());
            waitFor(() -> "inactive".equals(users.get(b)));
            assertThat(users).containsEntry(b, "inactive");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyOwnerAndItConfigureIt() throws Exception {
        String figma = subscription(department(), "Figma X");
        mvc.perform(put("/api/v1/license-pool/{id}/access", figma).with(as("finance", "f@" + domain))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"manual\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/license-pool/{id}/access", UUID.randomUUID()).with(it()))
                .andExpect(status().isNotFound());
    }
}
