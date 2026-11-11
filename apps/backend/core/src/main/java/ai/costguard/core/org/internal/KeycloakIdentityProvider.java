package ai.costguard.core.org.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Accounts in Keycloak through its admin API (ADR 0036). The core signs in as the confidential client
 * {@code costguard-registration}, whose service account may manage users and read realm roles. Keycloak itself
 * sends the e-mail to confirm the address and set a password, with the realm's SMTP settings.
 */
@Component
class KeycloakIdentityProvider implements IdentityProvider {

    static final String[] SETUP_ACTIONS = {"VERIFY_EMAIL", "UPDATE_PASSWORD"};

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RegistrationProperties.Keycloak config;
    private final long linkSeconds;
    private final HttpClient http;

    KeycloakIdentityProvider(RegistrationProperties properties) {
        this.config = properties.keycloak();
        this.linkSeconds = properties.linkLifespan().toSeconds();
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    private String base() {
        return config.url().replaceAll("/+$", "");
    }

    private String admin(String path) {
        return base() + "/admin/realms/" + config.realm() + path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String token() {
        String form = "grant_type=client_credentials&client_id=" + encode(config.clientId()) + "&client_secret="
                + encode(config.clientSecret());
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(base() + "/realms/" + config.realm() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        if (response.statusCode() != 200) {
            throw new Unavailable("Keycloak token: HTTP " + response.statusCode());
        }
        return read(response.body()).path("access_token").asText();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(config.timeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable("Keycloak: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Keycloak: interrupted");
        }
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new Unavailable("Keycloak: not JSON");
        }
    }

    private HttpRequest.Builder authorized(String token, String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
    }

    private static HttpRequest.BodyPublisher json(JsonNode body) {
        return HttpRequest.BodyPublishers.ofString(body.toString());
    }

    @Override
    public void saveMicrosoftLogin(MicrosoftLogin login) {
        String token = token();
        String instance = admin("/identity-provider/instances/" + encode(login.alias()));
        HttpResponse<String> existing = send(authorized(token, instance).GET());
        if (existing.statusCode() != 200 && existing.statusCode() != 404) {
            throw new Unavailable("Keycloak identity provider: HTTP " + existing.statusCode());
        }
        String authority = "https://login.microsoftonline.com/" + login.directoryId();
        ObjectNode config = JSON.createObjectNode()
                .put("issuer", authority + "/v2.0")
                .put("authorizationUrl", authority + "/oauth2/v2.0/authorize")
                .put("tokenUrl", authority + "/oauth2/v2.0/token")
                .put("logoutUrl", authority + "/oauth2/v2.0/logout")
                .put("jwksUrl", authority + "/discovery/v2.0/keys")
                .put("useJwksUrl", "true")
                .put("validateSignature", "true")
                .put("clientId", login.clientId())
                .put("clientAuthMethod", "client_secret_post")
                .put("defaultScope", "openid email profile")
                .put("pkceEnabled", "true")
                .put("pkceMethod", "S256")
                .put("syncMode", "IMPORT")
                .put("hideOnLoginPage", "true");
        if (login.clientSecret() != null && !login.clientSecret().isBlank()) {
            config.put("clientSecret", login.clientSecret());
        } else if (existing.statusCode() == 200) {
            // the masked value Keycloak returns keeps the stored secret
            config.put("clientSecret", read(existing.body()).path("config").path("clientSecret").asText());
        }
        ObjectNode provider = JSON.createObjectNode()
                .put("alias", login.alias())
                .put("displayName", login.displayName())
                .put("providerId", "oidc")
                .put("enabled", true)
                // not trusted: an existing account is linked only after its owner confirms by e-mail or password,
                // so another company's directory cannot take an account by setting someone's address
                .put("trustEmail", false)
                .put("storeToken", false)
                .put("hideOnLogin", true)
                .put("firstBrokerLoginFlowAlias", "costguard first broker login")
                .put("postBrokerLoginFlowAlias", "costguard post broker");
        provider.set("config", config);
        HttpResponse<String> saved = existing.statusCode() == 404
                ? send(authorized(token, admin("/identity-provider/instances")).header("Content-Type",
                        "application/json").POST(json(provider)))
                : send(authorized(token, instance).header("Content-Type", "application/json").PUT(json(provider)));
        if (saved.statusCode() / 100 != 2) {
            throw new Unavailable("Keycloak identity provider save: HTTP " + saved.statusCode());
        }
    }

    @Override
    public void removeLogin(String alias) {
        HttpResponse<String> r = send(authorized(token(), admin("/identity-provider/instances/" + encode(alias)))
                .DELETE());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw new Unavailable("Keycloak identity provider delete: HTTP " + r.statusCode());
        }
    }

    @Override
    public Optional<String> create(NewAccount account) {
        String token = token();
        ObjectNode user = JSON.createObjectNode()
                .put("username", account.email())
                .put("email", account.email())
                .put("firstName", account.firstName())
                .put("lastName", account.lastName())
                .put("enabled", true)
                .put("emailVerified", false);
        user.putObject("attributes").putArray("tenant_id").add(account.tenantId().toString());
        ArrayNode actions = user.putArray("requiredActions");
        for (String action : SETUP_ACTIONS) {
            actions.add(action);
        }
        HttpResponse<String> created = send(authorized(token, admin("/users"))
                .header("Content-Type", "application/json").POST(json(user)));
        if (created.statusCode() == 409) {
            return Optional.empty();
        }
        String location = created.headers().firstValue("Location").orElse("");
        if (created.statusCode() != 201 || !location.contains("/users/")) {
            throw new Unavailable("Keycloak create user: HTTP " + created.statusCode());
        }
        String id = location.substring(location.lastIndexOf('/') + 1);
        try {
            HttpResponse<String> role = send(authorized(token, admin("/roles/" + encode(account.role()))).GET());
            if (role.statusCode() != 200) {
                throw new Unavailable("Keycloak role " + account.role() + ": HTTP " + role.statusCode());
            }
            ArrayNode roles = JSON.createArrayNode().add(read(role.body()));
            HttpResponse<String> mapped = send(authorized(token, admin("/users/" + encode(id)
                    + "/role-mappings/realm")).header("Content-Type", "application/json").POST(json(roles)));
            if (mapped.statusCode() != 204) {
                throw new Unavailable("Keycloak role mapping: HTTP " + mapped.statusCode());
            }
        } catch (RuntimeException e) {
            // An account without its role would log in to nothing: take it back, so a retry starts clean.
            delete(id);
            throw e;
        }
        return Optional.of(id);
    }

    private static Account account(JsonNode user) {
        UUID tenant = null;
        for (JsonNode value : user.path("attributes").path("tenant_id")) {
            try {
                tenant = UUID.fromString(value.asText());
            } catch (IllegalArgumentException notATenant) {
                // an attribute that is not a UUID names no company
            }
        }
        return new Account(user.path("id").asText(), user.path("email").asText(), tenant,
                user.path("emailVerified").asBoolean(false));
    }

    @Override
    public Optional<Account> byEmail(String email) {
        String token = token();
        HttpResponse<String> response = send(authorized(token, admin("/users?exact=true&briefRepresentation=false"
                + "&email=" + encode(email))).GET());
        if (response.statusCode() != 200) {
            throw new Unavailable("Keycloak users: HTTP " + response.statusCode());
        }
        for (JsonNode user : read(response.body())) {
            if (email.equalsIgnoreCase(user.path("email").asText())) {
                return Optional.of(account(user));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Account> byId(String id) {
        String token = token();
        HttpResponse<String> response = send(authorized(token, admin("/users/" + encode(id))).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new Unavailable("Keycloak user: HTTP " + response.statusCode());
        }
        return Optional.of(account(read(response.body())));
    }

    @Override
    public void sendSetupEmail(String id) {
        String token = token();
        ArrayNode actions = JSON.createArrayNode();
        for (String action : SETUP_ACTIONS) {
            actions.add(action);
        }
        String query = "?client_id=" + encode(config.loginClientId()) + "&redirect_uri=" + encode(config.redirectUri())
                + "&lifespan=" + linkSeconds;
        HttpResponse<String> response = send(authorized(token, admin("/users/" + encode(id)
                + "/execute-actions-email" + query)).header("Content-Type", "application/json").PUT(json(actions)));
        if (response.statusCode() != 204) {
            throw new Unavailable("Keycloak setup e-mail: HTTP " + response.statusCode());
        }
    }

    @Override
    public void delete(String id) {
        String token = token();
        HttpResponse<String> response = send(authorized(token, admin("/users/" + encode(id))).DELETE());
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new Unavailable("Keycloak delete: HTTP " + response.statusCode());
        }
    }
}
