package ai.costguard.core.cabinet.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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

    // redirects are not followed (the default), so a vendor cannot send the request into the network either
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final boolean privateAllowed;

    /**
     * {@code costguard.access.scim-private-addresses}: true only in tests, whose SCIM server listens on 127.0.0.1.
     */
    ScimClient(@Value("${costguard.access.scim-private-addresses:false}") boolean privateAllowed) {
        this.privateAllowed = privateAllowed;
    }

    /**
     * The address an it_admin typed must be a vendor on the internet, not a service inside the network (finding of
     * 2026-11-13: the core would call it with a token and tell from the answer what is there). True when the host
     * is or resolves to a loopback, private, link-local, unique-local, shared (100.64/10) or unspecified address.
     * {@code unresolved} is the answer for a host that does not resolve.
     */
    boolean internal(String base, boolean unresolved) {
        if (privateAllowed) {
            return false;
        }
        String host;
        try {
            host = URI.create(base).getHost();
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (host == null) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (internal(a)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return unresolved;
        }
    }

    static boolean internal(InetAddress a) {
        byte[] b = a.getAddress();
        boolean uniqueLocal = a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc;
        boolean shared = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        boolean thisNetwork = b.length == 4 && b[0] == 0;
        return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress() || uniqueLocal || shared || thisNetwork;
    }

    private void requirePublic(String base) {
        if (internal(base, true)) {
            throw new Failed("SCIM: the address is not a vendor on the internet");
        }
    }

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
        requirePublic(base);
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
        requirePublic(base);
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
