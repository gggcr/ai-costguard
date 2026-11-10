package ai.costguard.core.org.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCIM 2.0 for identity providers (RFC 7644; ADR 0043): Okta, Entra ID, Keycloak, OneLogin, JumpCloud and the others
 * create, change and switch off employees here. Users only: groups mean nothing to CostGuard. Not in the OpenAPI of
 * the core: the protocol is the RFC's.
 */
@Hidden
@RestController
@RequestMapping(value = "/scim/v2", produces = {ScimController.SCIM_JSON, "application/json"})
class ScimController {

    static final String SCIM_JSON = "application/scim+json";
    static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

    private final ScimService scim;
    private final ScimWrites writes;

    ScimController(ScimService scim, ScimWrites writes) {
        this.writes = writes;
        this.scim = scim;
    }

    static String base(HttpServletRequest request) {
        String url = request.getRequestURL().toString();
        return url.substring(0, url.indexOf("/scim/v2") + "/scim/v2".length());
    }

    static String errorJson(int status, String scimType, String detail) {
        return "{\"schemas\":[\"" + ERROR + "\"],\"status\":\"" + status + "\""
                + (scimType == null ? "" : ",\"scimType\":\"" + scimType + "\"")
                + ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    @ExceptionHandler(ScimService.ScimError.class)
    ResponseEntity<Map<String, Object>> error(ScimService.ScimError e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ERROR));
        body.put("status", String.valueOf(e.status));
        if (e.scimType != null) {
            body.put("scimType", e.scimType);
        }
        body.put("detail", e.getMessage());
        return ResponseEntity.status(e.status).header("Content-Type", SCIM_JSON).body(body);
    }

    @GetMapping("/ServiceProviderConfig")
    Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        body.put("documentationUri", "https://github.com/gggcr/ai-costguard/blob/main/docs/runbooks/scim.md");
        body.put("patch", Map.of("supported", true));
        body.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        body.put("filter", Map.of("supported", true, "maxResults", ScimService.MAX_COUNT));
        body.put("changePassword", Map.of("supported", false));
        body.put("sort", Map.of("supported", false));
        body.put("etag", Map.of("supported", false));
        body.put("authenticationSchemes", List.of(Map.of("type", "oauthbearertoken", "name", "OAuth Bearer Token",
                "description", "A SCIM token issued in CostGuard (POST /api/v1/scim/tokens)", "primary", true)));
        return body;
    }

    @GetMapping("/ResourceTypes")
    Map<String, Object> resourceTypes(HttpServletRequest request) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"));
        user.put("id", "User");
        user.put("name", "User");
        user.put("endpoint", "/Users");
        user.put("schema", ScimService.USER);
        user.put("schemaExtensions", List.of(Map.of("schema", ScimService.ENTERPRISE, "required", false)));
        user.put("meta", Map.of("resourceType", "ResourceType", "location", base(request) + "/ResourceTypes/User"));
        return Map.of("schemas", List.of(ScimService.LIST), "totalResults", 1, "Resources", List.of(user));
    }

    @GetMapping("/Schemas")
    Map<String, Object> schemas() {
        List<Map<String, Object>> schemas = List.of(
                Map.of("id", ScimService.USER, "name", "User", "attributes", List.of(
                        attribute("userName", true), attribute("name", false), attribute("displayName", false),
                        attribute("emails", true), attribute("title", false), attribute("active", false),
                        attribute("externalId", false))),
                Map.of("id", ScimService.ENTERPRISE, "name", "EnterpriseUser",
                        "attributes", List.of(attribute("department", false))));
        return Map.of("schemas", List.of(ScimService.LIST), "totalResults", schemas.size(), "Resources", schemas);
    }

    private static Map<String, Object> attribute(String name, boolean required) {
        return Map.of("name", name, "required", required, "mutability", "readWrite");
    }

    @GetMapping("/Users")
    Map<String, Object> users(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count, HttpServletRequest request) {
        return scim.list(auth, filter, startIndex, count, base(request));
    }

    @GetMapping("/Users/{id}")
    Map<String, Object> user(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @PathVariable String id, HttpServletRequest request) {
        return scim.get(auth, id, base(request));
    }

    @PostMapping(value = "/Users", consumes = {SCIM_JSON, "application/json"})
    ResponseEntity<Map<String, Object>> create(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> user = writes.create(auth, body, base(request));
        @SuppressWarnings("unchecked")
        String location = String.valueOf(((Map<String, Object>) user.get("meta")).get("location"));
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", location)
                .header("Content-Type", SCIM_JSON).body(user);
    }

    @PutMapping(value = "/Users/{id}", consumes = {SCIM_JSON, "application/json"})
    Map<String, Object> replace(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return writes.replace(auth, id, body, base(request));
    }

    @PatchMapping(value = "/Users/{id}", consumes = {SCIM_JSON, "application/json"})
    Map<String, Object> patch(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return writes.patch(auth, id, body, base(request));
    }

    @DeleteMapping("/Users/{id}")
    ResponseEntity<Void> delete(@RequestAttribute(ScimSecurity.AUTH_ATTRIBUTE) ScimTokens.Auth auth,
            @PathVariable String id) {
        writes.delete(auth, id);
        return ResponseEntity.noContent().build();
    }

    /** A body that is not JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return error(new ScimService.ScimError(400, "invalidSyntax", "The body is not a JSON object"));
    }

    @GetMapping("/Groups")
    ResponseEntity<Map<String, Object>> groups() {
        // providers probe Groups; an empty list says "none" more gently than 404
        return ResponseEntity.ok().header("Content-Type", SCIM_JSON).body(Map.of("schemas", List.of(ScimService.LIST),
                "totalResults", 0, "startIndex", 1, "itemsPerPage", 0, "Resources", List.of()));
    }
}
