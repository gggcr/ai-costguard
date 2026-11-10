package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The admin API calls against a stand-in for Keycloak: what is sent, and what each answer means (ADR 0032). */
class KeycloakIdentityProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Call(String method, String path, String query, String body) {
    }

    record Answer(int status, String body, Map<String, String> headers) {
        static Answer of(int status, String body) {
            return new Answer(status, body, Map.of());
        }
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, Answer> answers = call -> Answer.of(404, "");
    private KeycloakIdentityProvider keycloak;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        keycloak = new KeycloakIdentityProvider(new RegistrationProperties(
                new RegistrationProperties.Keycloak(url, "costguard", "costguard-registration", "s3cret",
                        "costguard-frontend", "https://app.example/", Duration.ofSeconds(5)),
                Duration.ofHours(24), Duration.ofHours(48), Duration.ofMinutes(15),
                new RegistrationProperties.RateLimit(5, 3, Duration.ofHours(1))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        calls.add(call);
        Answer answer = call.path().endsWith("/protocol/openid-connect/token")
                ? Answer.of(200, "{\"access_token\":\"tok\"}") : answers.apply(call);
        answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private List<Call> admin() {
        return calls.stream().filter(c -> c.path().startsWith("/admin/")).toList();
    }

    @Test
    void createsAnAccountThatMustConfirmTheEmailAndSetAPasswordWithTheCompanyAndTheRole() throws Exception {
        UUID tenant = UUID.randomUUID();
        answers = call -> switch (call.method() + " " + call.path()) {
            case "POST /admin/realms/costguard/users" -> new Answer(201, "",
                    Map.of("Location", "http://kc/admin/realms/costguard/users/u-1"));
            case "GET /admin/realms/costguard/roles/finance" -> Answer.of(200, "{\"id\":\"r-9\",\"name\":\"finance\"}");
            case "POST /admin/realms/costguard/users/u-1/role-mappings/realm" -> Answer.of(204, "");
            default -> Answer.of(500, "");
        };

        assertThat(keycloak.create(new IdentityProvider.NewAccount("a@acme.example", "Анна", "Петрова", tenant,
                "finance"))).contains("u-1");

        JsonNode user = JSON.readTree(admin().get(0).body());
        assertThat(user.path("username").asText()).isEqualTo("a@acme.example");
        assertThat(user.path("emailVerified").asBoolean()).isFalse();
        assertThat(user.path("enabled").asBoolean()).isTrue();
        assertThat(user.path("attributes").path("tenant_id").get(0).asText()).isEqualTo(tenant.toString());
        assertThat(user.path("requiredActions").toString()).isEqualTo("[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]");
        assertThat(JSON.readTree(admin().get(2).body()).get(0).path("name").asText()).isEqualTo("finance");
        assertThat(calls.stream().filter(c -> c.path().endsWith("/token")).findFirst().orElseThrow().body())
                .contains("grant_type=client_credentials", "client_id=costguard-registration",
                        "client_secret=s3cret");
    }

    @Test
    void anEmailWithAnAccountCreatesNothing() {
        answers = call -> Answer.of(409, "{\"errorMessage\":\"User exists with same email\"}");

        assertThat(keycloak.create(new IdentityProvider.NewAccount("a@acme.example", "A", "B", UUID.randomUUID(),
                "owner"))).isEmpty();
    }

    @Test
    void anAccountWhoseRoleCannotBeSetIsTakenBack() {
        answers = call -> switch (call.method() + " " + call.path()) {
            case "POST /admin/realms/costguard/users" -> new Answer(201, "",
                    Map.of("Location", "http://kc/admin/realms/costguard/users/u-2"));
            case "GET /admin/realms/costguard/roles/owner" -> Answer.of(200, "{\"name\":\"owner\"}");
            case "POST /admin/realms/costguard/users/u-2/role-mappings/realm" -> Answer.of(403, "");
            case "DELETE /admin/realms/costguard/users/u-2" -> Answer.of(204, "");
            default -> Answer.of(500, "");
        };

        assertThatThrownBy(() -> keycloak.create(new IdentityProvider.NewAccount("a@acme.example", "A", "B",
                UUID.randomUUID(), "owner"))).isInstanceOf(IdentityProvider.Unavailable.class);
        assertThat(admin()).extracting(Call::method).containsExactly("POST", "GET", "POST", "DELETE");
    }

    @Test
    void theSetupEmailAsksForBothActionsWithTheLoginClientAndTheLinkLifespan() {
        answers = call -> Answer.of(204, "");

        keycloak.sendSetupEmail("u-1");

        Call call = admin().getFirst();
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.path()).isEqualTo("/admin/realms/costguard/users/u-1/execute-actions-email");
        assertThat(call.query()).contains("client_id=costguard-frontend", "lifespan=86400",
                "redirect_uri=https%3A%2F%2Fapp.example%2F");
        assertThat(call.body()).isEqualTo("[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]");
    }

    @Test
    void readsTheCompanyAndTheConfirmationOfAnAccount() {
        UUID tenant = UUID.randomUUID();
        answers = call -> Answer.of(200, """
                [{"id":"u-3","email":"A@Acme.example","emailVerified":true,"attributes":{"tenant_id":["%s"]}},
                 {"id":"u-4","email":"other@acme.example","emailVerified":false}]
                """.formatted(tenant));

        IdentityProvider.Account account = keycloak.byEmail("a@acme.example").orElseThrow();

        assertThat(account).isEqualTo(new IdentityProvider.Account("u-3", "A@Acme.example", tenant, true));
        assertThat(admin().getFirst().query()).contains("exact=true", "email=a%40acme.example");
    }

    @Test
    void aMissingAccountIsEmptyAndDeletingItTwiceIsFine() {
        answers = call -> Answer.of(404, "");

        assertThat(keycloak.byId("gone")).isEmpty();
        keycloak.delete("gone");
    }

    @Test
    void anUnexpectedAnswerIsUnavailableNotAGuess() {
        answers = call -> Answer.of(500, "");

        assertThatThrownBy(() -> keycloak.byEmail("a@acme.example")).isInstanceOf(IdentityProvider.Unavailable.class);
        assertThatThrownBy(() -> keycloak.sendSetupEmail("u-1")).isInstanceOf(IdentityProvider.Unavailable.class);
    }
}
