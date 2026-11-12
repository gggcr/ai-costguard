package ai.costguard.core.cabinet.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * A vendor's SCIM 2.0 API (RFC 7644): Figma, Slack, Zoom, Miro, Notion, Atlassian… (ADR 0065). The person is found
 * by userName, which these vendors keep as the e-mail.
 */
@Component
class ScimClient {

    static class Failed extends RuntimeException {
        Failed(String what) {
            super(what);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Failed("SCIM: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("SCIM: interrupted");
        }
    }

    private static HttpRequest.Builder request(String base, String token, String path) {
        return HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Accept", "application/scim+json");
    }

    private String find(String base, String token, String email) {
        HttpResponse<String> r = send(request(base, token, "/Users?filter=" + URLEncoder.encode(
                "userName eq \"" + email.replace("\"", "") + "\"", StandardCharsets.UTF_8)).GET().build());
        if (r.statusCode() != 200) {
            throw new Failed("SCIM find: HTTP " + r.statusCode());
        }
        try {
            JsonNode found = JSON.readTree(r.body()).path("Resources");
            return found.isArray() && found.size() > 0 ? found.get(0).path("id").asText() : null;
        } catch (IOException e) {
            throw new Failed("SCIM find: unreadable answer");
        }
    }

    private void active(String base, String token, String id, boolean active) {
        ObjectNode patch = JSON.createObjectNode();
        patch.putArray("schemas").add(PATCH);
        patch.putArray("Operations").addObject().put("op", "replace").put("path", "active").put("value", active);
        HttpResponse<String> r = send(request(base, token, "/Users/" + id).header("Content-Type", "application/scim+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patch.toString())).build());
        if (r.statusCode() / 100 != 2) {
            throw new Failed("SCIM active=" + active + ": HTTP " + r.statusCode());
        }
    }

    /** @return what was done, for the log */
    String grant(String base, String token, String email, String firstName, String lastName) {
        String id = find(base, token, email);
        if (id != null) {
            active(base, token, id, true);
            return "активирован";
        }
        ObjectNode user = JSON.createObjectNode();
        user.putArray("schemas").add(USER);
        user.put("userName", email).put("active", true);
        user.putObject("name").put("givenName", firstName).put("familyName", lastName);
        user.putArray("emails").addObject().put("value", email).put("primary", true).put("type", "work");
        HttpResponse<String> r = send(request(base, token, "/Users").header("Content-Type", "application/scim+json")
                .POST(HttpRequest.BodyPublishers.ofString(user.toString())).build());
        if (r.statusCode() != 201 && r.statusCode() != 409) {
            throw new Failed("SCIM create: HTTP " + r.statusCode());
        }
        return "создан";
    }

    String revoke(String base, String token, String email, boolean delete) {
        String id = find(base, token, email);
        if (id == null) {
            return "не найден у поставщика";
        }
        if (delete) {
            HttpResponse<String> r = send(request(base, token, "/Users/" + id).DELETE().build());
            if (r.statusCode() != 204 && r.statusCode() != 404) {
                throw new Failed("SCIM delete: HTTP " + r.statusCode());
            }
            return "удалён";
        }
        active(base, token, id, false);
        return "деактивирован";
    }
}
