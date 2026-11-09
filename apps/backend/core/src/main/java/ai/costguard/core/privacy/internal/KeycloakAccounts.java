package ai.costguard.core.privacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Keycloak accounts of a person or a company, through the admin API (ADR 0021). The core signs in as the
 * confidential client {@code costguard-privacy}, whose service account may only view and manage users. An account
 * counts as the tenant's only when its {@code tenant_id} attribute says so: an e-mail alone could be another
 * company's user.
 */
@Component
class KeycloakAccounts {

    /** Keycloak did not answer as expected; nothing in the database changes, the erasure is tried again. */
    static class Unavailable extends RuntimeException {
        Unavailable(String what) {
            super(what);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 100;

    private final PrivacyProperties.Keycloak config;
    private final HttpClient http;

    KeycloakAccounts(PrivacyProperties properties) {
        this.config = properties.keycloak();
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    boolean configured() {
        return config.configured();
    }

    private String base() {
        return config.url().replaceAll("/+$", "");
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

    private JsonNode get(String token, String pathAndQuery) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(base() + "/admin/realms/" + config.realm() + pathAndQuery))
                .header("Authorization", "Bearer " + token).GET());
        if (response.statusCode() != 200) {
            throw new Unavailable("Keycloak users: HTTP " + response.statusCode());
        }
        return read(response.body());
    }

    private static boolean ofTenant(JsonNode user, UUID tenantId) {
        for (JsonNode value : user.path("attributes").path("tenant_id")) {
            if (tenantId.toString().equalsIgnoreCase(value.asText())) {
                return true;
            }
        }
        return false;
    }

    /** The ids of the tenant's accounts with these e-mails. */
    List<String> find(UUID tenantId, Collection<String> emails) {
        String token = token();
        Set<String> ids = new LinkedHashSet<>();
        for (String email : emails) {
            for (JsonNode user : get(token, "/users?exact=true&briefRepresentation=false&email=" + encode(email))) {
                if (ofTenant(user, tenantId)) {
                    ids.add(user.path("id").asText());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Every account of the tenant, found by its attribute. */
    List<String> ofTenant(UUID tenantId) {
        String token = token();
        List<String> ids = new ArrayList<>();
        for (int first = 0; ; first += PAGE) {
            JsonNode page = get(token, "/users?briefRepresentation=false&max=" + PAGE + "&first=" + first + "&q="
                    + encode("tenant_id:" + tenantId));
            for (JsonNode user : page) {
                if (ofTenant(user, tenantId)) {
                    ids.add(user.path("id").asText());
                }
            }
            if (page.size() < PAGE) {
                return ids;
            }
        }
    }

    /** Deleting an account that is gone already is fine: a retried erasure meets the ones it deleted before. */
    void delete(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String token = token();
        for (String id : ids) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                    URI.create(base() + "/admin/realms/" + config.realm() + "/users/" + encode(id)))
                    .header("Authorization", "Bearer " + token).DELETE());
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                throw new Unavailable("Keycloak delete: HTTP " + response.statusCode());
            }
        }
    }
}
