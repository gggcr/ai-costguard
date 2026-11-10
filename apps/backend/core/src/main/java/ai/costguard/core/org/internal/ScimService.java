package ai.costguard.core.org.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SCIM 2.0 User resource over the employees of a company (RFC 7643, 7644; ADR 0043). The id of a User is the id of
 * the employee; userName is what the identity provider sent, or the e-mail for an employee made by hand, so a
 * provider that matches by userName finds the people who were there before it. Erased employees are not shown.
 */
@Service
class ScimService {

    static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String ENTERPRISE = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    static final String LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final int MAX_COUNT = 200;

    /** The filters providers send to find one user: attribute eq "value". */
    private static final Pattern FILTER = Pattern.compile(
            "^\\s*(userName|externalId|emails\\.value|emails|id)\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** An error in SCIM's shape (RFC 7644 §3.12). */
    static class ScimError extends RuntimeException {
        final int status;
        final String scimType;

        ScimError(int status, String scimType, String detail) {
            super(detail);
            this.status = status;
            this.scimType = scimType;
        }
    }

    private final OrgRepository org;
    private final ScimRepository scim;
    private final TenantSession session;

    ScimService(OrgRepository org, ScimRepository scim, TenantSession session) {
        this.org = org;
        this.scim = scim;
        this.session = session;
    }

    @Transactional(readOnly = true)
    Map<String, Object> list(ScimTokens.Auth auth, String filter, Integer startIndex, Integer count, String base) {
        session.bind(auth.tenantId());
        Map<UUID, ScimRepository.Link> links = scim.links();
        Predicate<Map<String, Object>> match = filter == null || filter.isBlank() ? u -> true : matcher(filter);
        Map<UUID, String> departments = departments();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Employee e : org.employees(null)) {
            if (e.erased()) {
                continue;
            }
            Map<String, Object> user = resource(e, links.get(e.id()), departments, base);
            if (match.test(user)) {
                all.add(user);
            }
        }
        int start = Math.max(startIndex == null ? 1 : startIndex, 1);
        int size = Math.min(Math.max(count == null ? 100 : count, 0), MAX_COUNT);
        List<Map<String, Object>> page = all.subList(Math.min(start - 1, all.size()),
                Math.min(start - 1 + size, all.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(LIST));
        body.put("totalResults", all.size());
        body.put("startIndex", start);
        body.put("itemsPerPage", page.size());
        body.put("Resources", page);
        return body;
    }

    @Transactional(readOnly = true)
    Map<String, Object> get(ScimTokens.Auth auth, String id, String base) {
        session.bind(auth.tenantId());
        Employee e = employee(id);
        return resource(e, scim.link(e.id()).orElse(null), departments(), base);
    }

    Employee employee(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ScimError(404, null, "No such user");
        }
        return org.employee(uuid).filter(e -> !e.erased()).orElseThrow(() -> new ScimError(404, null, "No such user"));
    }

    Map<UUID, String> departments() {
        Map<UUID, String> out = new HashMap<>();
        org.departments().forEach(d -> out.put(d.id(), d.name()));
        return out;
    }

    static Map<String, Object> resource(Employee e, ScimRepository.Link link, Map<UUID, String> departments,
            String base) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of(USER, ENTERPRISE));
        user.put("id", e.id().toString());
        if (link != null && link.externalId() != null) {
            user.put("externalId", link.externalId());
        }
        user.put("userName", link != null ? link.userName() : e.email());
        user.put("name", Map.of("formatted", e.name()));
        user.put("displayName", e.name());
        user.put("emails", List.of(Map.of("value", e.email(), "type", "work", "primary", true)));
        user.put("title", e.position());
        user.put("active", e.active() && (link == null || link.active()));
        Map<String, Object> enterprise = new LinkedHashMap<>();
        enterprise.put("department", departments.getOrDefault(e.departmentId(), ""));
        user.put(ENTERPRISE, enterprise);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", "User");
        if (link != null) {
            meta.put("created", link.createdAt().toString());
            meta.put("lastModified", link.updatedAt().toString());
        }
        meta.put("location", base + "/Users/" + e.id());
        user.put("meta", meta);
        return user;
    }

    @SuppressWarnings("unchecked")
    static Predicate<Map<String, Object>> matcher(String filter) {
        Matcher m = FILTER.matcher(filter);
        if (!m.matches()) {
            throw new ScimError(400, "invalidFilter", "Only attribute eq \"value\" filters on userName, externalId, "
                    + "emails.value and id are supported");
        }
        String attribute = m.group(1).toLowerCase(Locale.ROOT);
        String value = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
        return user -> switch (attribute) {
            // userName and e-mail compare without case, as RFC 7643 says of userName
            case "username" -> value.equalsIgnoreCase(String.valueOf(user.get("userName")));
            case "externalid" -> value.equals(user.get("externalId"));
            case "id" -> value.equals(user.get("id"));
            default -> ((List<Map<String, Object>>) user.get("emails")).stream()
                    .anyMatch(e -> value.equalsIgnoreCase(String.valueOf(e.get("value"))));
        };
    }

    static Optional<String> text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? Optional.empty()
                : Optional.of(String.valueOf(value).strip());
    }
}
