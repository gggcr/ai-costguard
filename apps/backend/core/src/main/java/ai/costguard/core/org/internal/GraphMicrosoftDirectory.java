package ai.costguard.core.org.internal;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Microsoft Graph v1.0 with the app's own token (client credentials). */
@Component
class GraphMicrosoftDirectory implements MicrosoftDirectory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String graph;
    private final String login;

    GraphMicrosoftDirectory(@Value("${costguard.microsoft.graph-url:https://graph.microsoft.com}") String graph,
            @Value("${costguard.microsoft.login-url:https://login.microsoftonline.com}") String login) {
        this.graph = graph.replaceAll("/+$", "");
        this.login = login.replaceAll("/+$", "");
    }

    private static String form(String key, String value) {
        return key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable("Microsoft: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Microsoft: interrupted");
        }
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new Unavailable("Microsoft: unreadable answer");
        }
    }

    private String token(App app) {
        String body = String.join("&", form("client_id", app.clientId()), form("client_secret", app.clientSecret()),
                form("scope", "https://graph.microsoft.com/.default"), form("grant_type", "client_credentials"));
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(login + "/"
                        + URLEncoder.encode(app.directoryId(), StandardCharsets.UTF_8) + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (r.statusCode() != 200) {
            throw new Unavailable("Microsoft token: HTTP " + r.statusCode());
        }
        return read(r.body()).path("access_token").asText();
    }

    private HttpRequest.Builder graph(String token, String path) {
        return HttpRequest.newBuilder(URI.create(graph + "/v1.0" + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token);
    }

    @Override
    public Mailbox create(App app, NewMailbox m) {
        String token = token(app);
        String address = null;
        for (int i = 1; i <= 50 && address == null; i++) {
            String candidate = m.nickname() + (i == 1 ? "" : String.valueOf(i)) + "@" + m.domain();
            HttpResponse<String> r = send(graph(token, "/users/" + URLEncoder.encode(candidate, StandardCharsets.UTF_8)
                    + "?$select=id").GET().build());
            if (r.statusCode() == 404) {
                address = candidate;
            } else if (r.statusCode() != 200) {
                throw new Unavailable("Microsoft user lookup: HTTP " + r.statusCode());
            }
        }
        if (address == null) {
            throw new Unavailable("Microsoft: no free address for " + m.nickname());
        }
        ObjectNode user = JSON.createObjectNode()
                .put("accountEnabled", true)
                .put("displayName", m.firstName() + " " + m.lastName())
                .put("givenName", m.firstName())
                .put("surname", m.lastName())
                .put("mailNickname", address.substring(0, address.indexOf('@')))
                .put("userPrincipalName", address);
        if (m.usageLocation() != null) {
            user.put("usageLocation", m.usageLocation());
        }
        user.putObject("passwordProfile").put("forceChangePasswordNextSignIn", false).put("password", m.password());
        HttpResponse<String> created = send(graph(token, "/users").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(user.toString())).build());
        if (created.statusCode() != 201) {
            throw new Unavailable("Microsoft create user: HTTP " + created.statusCode() + " " + created.body());
        }
        String id = read(created.body()).path("id").asText();
        if (m.licenseSku() != null) {
            ObjectNode license = JSON.createObjectNode();
            license.putArray("addLicenses").addObject().put("skuId", m.licenseSku()).putArray("disabledPlans");
            license.putArray("removeLicenses");
            HttpResponse<String> assigned = send(graph(token, "/users/" + id + "/assignLicense")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(license.toString())).build());
            if (assigned.statusCode() != 200) {
                delete(app, id);
                throw new Unavailable("Microsoft license: HTTP " + assigned.statusCode() + " " + assigned.body());
            }
        }
        return new Mailbox(id, address);
    }

    @Override
    public void disable(App app, String address) {
        String token = token(app);
        String user = "/users/" + URLEncoder.encode(address, StandardCharsets.UTF_8);
        HttpResponse<String> r = send(graph(token, user).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"accountEnabled\":false}")).build());
        if (r.statusCode() == 404) {
            return;
        }
        if (r.statusCode() != 204) {
            throw new Unavailable("Microsoft disable user: HTTP " + r.statusCode());
        }
        send(graph(token, user + "/revokeSignInSessions").POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    @Override
    public java.util.List<SignIn> signIns(App app, java.time.Instant from) {
        String token = token(app);
        java.util.List<SignIn> out = new java.util.ArrayList<>();
        String next = graph + "/v1.0/auditLogs/signIns?$top=999&$select=userPrincipalName,appDisplayName,"
                + "createdDateTime,status&$filter=" + URLEncoder.encode("createdDateTime ge "
                        + from.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), StandardCharsets.UTF_8);
        for (int page = 0; next != null && page < 200; page++) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(next)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token).GET().build());
            if (r.statusCode() != 200) {
                throw new Unavailable("Microsoft sign-ins: HTTP " + r.statusCode());
            }
            JsonNode body = read(r.body());
            for (JsonNode s : body.path("value")) {
                // only successful sign-ins mean the program is used
                if (s.path("status").path("errorCode").asInt(0) != 0) {
                    continue;
                }
                out.add(new SignIn(s.path("userPrincipalName").asText().toLowerCase(java.util.Locale.ROOT),
                        s.path("appDisplayName").asText(), java.time.Instant.parse(s.path("createdDateTime").asText())));
            }
            next = body.hasNonNull("@odata.nextLink") ? body.path("@odata.nextLink").asText() : null;
        }
        return out;
    }

    @Override
    public java.util.List<EnterpriseApp> enterpriseApps(App app) {
        String token = token(app);
        java.util.List<EnterpriseApp> out = new java.util.ArrayList<>();
        String next = graph + "/v1.0/servicePrincipals?$top=999&$select=id,displayName&$filter="
                + URLEncoder.encode("tags/any(t:t eq 'WindowsAzureActiveDirectoryIntegratedApp')", StandardCharsets.UTF_8);
        for (int page = 0; next != null && page < 50; page++) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(next)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).header("ConsistencyLevel", "eventual").GET().build());
            if (r.statusCode() != 200) {
                throw new Unavailable("Microsoft apps: HTTP " + r.statusCode());
            }
            JsonNode body = read(r.body());
            body.path("value").forEach(s -> out.add(new EnterpriseApp(s.path("id").asText(),
                    s.path("displayName").asText())));
            next = body.hasNonNull("@odata.nextLink") ? body.path("@odata.nextLink").asText() : null;
        }
        out.sort(java.util.Comparator.comparing(EnterpriseApp::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private String userId(String token, String email) {
        HttpResponse<String> r = send(graph(token, "/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "?$select=id").GET().build());
        if (r.statusCode() == 404) {
            throw new Unavailable("Microsoft: no user " + email);
        }
        if (r.statusCode() != 200) {
            throw new Unavailable("Microsoft user: HTTP " + r.statusCode());
        }
        return read(r.body()).path("id").asText();
    }

    @Override
    public void assign(App app, String sp, String email) {
        String token = token(app);
        String user = userId(token, email);
        HttpResponse<String> roles = send(graph(token, "/servicePrincipals/" + sp + "?$select=appRoles").GET().build());
        if (roles.statusCode() != 200) {
            throw new Unavailable("Microsoft app: HTTP " + roles.statusCode());
        }
        // the app's role for users, or the default access when it has none
        String role = "00000000-0000-0000-0000-000000000000";
        for (JsonNode r : read(roles.body()).path("appRoles")) {
            boolean forUsers = false;
            for (JsonNode t : r.path("allowedMemberTypes")) {
                forUsers |= "User".equals(t.asText());
            }
            if (forUsers && r.path("isEnabled").asBoolean(true)) {
                role = r.path("id").asText();
                break;
            }
        }
        ObjectNode body = JSON.createObjectNode().put("principalId", user).put("resourceId", sp).put("appRoleId", role);
        HttpResponse<String> r = send(graph(token, "/servicePrincipals/" + sp + "/appRoleAssignedTo")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        // 400 with "already exists" is an assignment made before
        if (r.statusCode() != 201 && !(r.statusCode() == 400 && r.body().contains("already exists"))) {
            throw new Unavailable("Microsoft assign: HTTP " + r.statusCode() + " " + r.body());
        }
    }

    @Override
    public void unassign(App app, String sp, String email) {
        String token = token(app);
        String user = userId(token, email);
        HttpResponse<String> list = send(graph(token, "/servicePrincipals/" + sp + "/appRoleAssignedTo?$filter="
                + URLEncoder.encode("principalId eq " + user, StandardCharsets.UTF_8)).GET().build());
        if (list.statusCode() != 200) {
            throw new Unavailable("Microsoft assignments: HTTP " + list.statusCode());
        }
        for (JsonNode a : read(list.body()).path("value")) {
            HttpResponse<String> r = send(graph(token, "/servicePrincipals/" + sp + "/appRoleAssignedTo/"
                    + a.path("id").asText()).DELETE().build());
            if (r.statusCode() != 204 && r.statusCode() != 404) {
                throw new Unavailable("Microsoft unassign: HTTP " + r.statusCode());
            }
        }
    }

    @Override
    public void delete(App app, String id) {
        HttpResponse<String> r = send(graph(token(app), "/users/" + id).DELETE().build());
        if (r.statusCode() != 204 && r.statusCode() != 404) {
            throw new Unavailable("Microsoft delete user: HTTP " + r.statusCode());
        }
    }
}
