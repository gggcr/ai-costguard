package ai.costguard.core.org.internal;

import ai.costguard.core.org.internal.ScimService.ScimError;
import ai.costguard.platform.errors.ApiException;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an identity provider changes over SCIM (RFC 7644; ADR 0043): POST, PUT, PATCH and DELETE of Users. Employees
 * change through the org service, so plan limits, events and the activity log are the same as for a change by hand.
 */
@Service
class ScimWrites {

    /** Where a provider puts a person it names no department for. */
    static final String NO_DEPARTMENT = "Без отдела";
    /** Who changed the employee, for the activity log. */
    static final String WHO = "scim";

    private final OrgRepository org;
    private final ScimRepository scim;
    private final ScimService reads;
    private final OrgService people;
    private final TenantSession session;
    private final Clock clock;

    ScimWrites(OrgRepository org, ScimRepository scim, ScimService reads, OrgService people, TenantSession session,
            Clock clock) {
        this.org = org;
        this.scim = scim;
        this.reads = reads;
        this.people = people;
        this.session = session;
        this.clock = clock;
    }

    /** What a provider sends of a person, read from a User resource. */
    record Person(String userName, String externalId, String name, String email, String title, String department,
            boolean active) {
    }

    @SuppressWarnings("unchecked")
    static Person read(Map<String, Object> body) {
        String userName = ScimService.text(body.get("userName"))
                .orElseThrow(() -> new ScimError(400, "invalidValue", "userName is required"));
        Map<String, Object> name = body.get("name") instanceof Map<?, ?> n ? (Map<String, Object>) n : Map.of();
        String parts = String.join(" ", ScimService.text(name.get("familyName")).orElse(""),
                ScimService.text(name.get("givenName")).orElse(""),
                ScimService.text(name.get("middleName")).orElse("")).strip().replaceAll("\\s+", " ");
        String formatted = ScimService.text(name.get("formatted")).or(() -> ScimService.text(parts))
                .or(() -> ScimService.text(body.get("displayName"))).orElse(userName);
        String email = null;
        if (body.get("emails") instanceof List<?> emails) {
            Map<String, Object> chosen = null;
            for (Object o : emails) {
                if (o instanceof Map<?, ?> e && (chosen == null || Boolean.TRUE.equals(bool(e.get("primary"))))) {
                    chosen = (Map<String, Object>) e;
                }
            }
            email = chosen == null ? null : ScimService.text(chosen.get("value")).orElse(null);
        }
        if (email == null && userName.contains("@")) {
            email = userName;
        }
        if (email == null) {
            throw new ScimError(400, "invalidValue", "An e-mail is required: in emails or as the userName");
        }
        Map<String, Object> enterprise = body.get(ScimService.ENTERPRISE) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Boolean active = bool(body.get("active"));
        return new Person(userName, ScimService.text(body.get("externalId")).orElse(null), formatted,
                email.toLowerCase(Locale.ROOT), ScimService.text(body.get("title")).orElse(null),
                ScimService.text(enterprise.get("department")).orElse(null), active == null || active);
    }

    /** Entra ID sends booleans as strings too: "False". */
    static Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = value == null ? "" : String.valueOf(value).strip();
        return text.equalsIgnoreCase("true") ? Boolean.TRUE : text.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    /** The department by its name, made when new; {@link #NO_DEPARTMENT} when the provider names none. */
    private UUID department(UUID tenantId, String name) {
        String wanted = name == null || name.isBlank() ? NO_DEPARTMENT : name.strip();
        return org.departments().stream().filter(d -> d.name().equalsIgnoreCase(wanted)).map(Department::id)
                .findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    org.insertDepartment(tenantId, id, wanted);
                    return id;
                });
    }

    private EmployeeDetails details(UUID tenantId, Person p, Employee current) {
        String title = p.title() != null ? p.title() : current != null ? current.position() : "Сотрудник";
        return new EmployeeDetails(p.name(), p.email(), department(tenantId, p.department()), title);
    }

    /** A refusal of the core in SCIM's words: a taken e-mail is uniqueness, a plan limit is forbidden. */
    private static ScimError refused(ApiException e) {
        int status = e.problem().status().value();
        String type = status == 409 ? "uniqueness" : status == 400 ? "invalidValue" : null;
        return new ScimError(status, type, e.problem().detail());
    }

    /**
     * A new User. An employee made by hand with this e-mail and no provider yet is taken over, not duplicated: a
     * provider told 409 would stop provisioning that person.
     */
    @Transactional
    Map<String, Object> create(ScimTokens.Auth auth, Map<String, Object> body, String base) {
        session.bind(auth.tenantId());
        Person p = read(body);
        Map<UUID, ScimRepository.Link> links = scim.links();
        if (links.values().stream().anyMatch(l -> l.userName().equalsIgnoreCase(p.userName()))) {
            throw new ScimError(409, "uniqueness", "A user with this userName exists");
        }
        Optional<Employee> existing = org.employees(null).stream()
                .filter(e -> !e.erased() && e.email().equalsIgnoreCase(p.email())).findFirst();
        if (existing.isPresent() && links.containsKey(existing.get().id())) {
            throw new ScimError(409, "uniqueness", "A user with this e-mail exists");
        }
        UUID id;
        try {
            id = existing.isPresent()
                    ? people.updateEmployee(auth.tenantId(), existing.get().id(),
                            details(auth.tenantId(), p, existing.get())).id()
                    : people.createEmployee(auth.tenantId(), details(auth.tenantId(), p, null)).id();
        } catch (ApiException e) {
            throw refused(e);
        }
        scim.save(auth.tenantId(), id, p.externalId(), p.userName(), p.active(), clock.instant());
        deactivated(auth, id, p.active());
        return reads.get(auth, id.toString(), base);
    }

    /** PUT: the provider's whole picture of the person. */
    @Transactional
    Map<String, Object> replace(ScimTokens.Auth auth, String id, Map<String, Object> body, String base) {
        session.bind(auth.tenantId());
        Employee e = reads.employee(id);
        Person p = read(body);
        boolean taken = scim.links().values().stream()
                .anyMatch(l -> l.userName().equalsIgnoreCase(p.userName()) && !l.employeeId().equals(e.id()));
        if (taken) {
            throw new ScimError(409, "uniqueness", "Another user has this userName");
        }
        if (e.active()) {
            try {
                people.updateEmployee(auth.tenantId(), e.id(), details(auth.tenantId(), p, e));
            } catch (ApiException x) {
                throw refused(x);
            }
        }
        scim.save(auth.tenantId(), e.id(), p.externalId(), p.userName(), p.active(), clock.instant());
        deactivated(auth, e.id(), p.active());
        return reads.get(auth, id, base);
    }

    /**
     * Switched off: with the token's dismissOnDeactivate the employee is dismissed (the checklist and the findings
     * follow), otherwise only the account is marked. active = true takes no dismissed employee back: hiring someone
     * again is a person's decision.
     */
    private void deactivated(ScimTokens.Auth auth, UUID employeeId, boolean active) {
        if (!active && auth.dismissOnDeactivate()) {
            people.dismiss(auth.tenantId(), employeeId, WHO);
        }
    }

    /**
     * PATCH (RFC 7644 §3.5.2) as Okta and Entra ID send it: add, replace and remove of active, userName, externalId,
     * displayName, name, title, emails and the department of EnterpriseUser, with a path or as an object of values. The
     * result is applied like a PUT.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    Map<String, Object> patch(ScimTokens.Auth auth, String id, Map<String, Object> body, String base) {
        session.bind(auth.tenantId());
        Employee e = reads.employee(id);
        Map<String, Object> user = new LinkedHashMap<>(ScimService.resource(e, scim.link(e.id()).orElse(null),
                reads.departments(), base));
        Map<String, Object> enterprise = new LinkedHashMap<>((Map<String, Object>) user.get(ScimService.ENTERPRISE));
        user.put(ScimService.ENTERPRISE, enterprise);
        if (!(body.get("Operations") instanceof List<?> operations)) {
            throw new ScimError(400, "invalidSyntax", "Operations are required");
        }
        for (Object o : operations) {
            if (!(o instanceof Map<?, ?> op)) {
                throw new ScimError(400, "invalidSyntax", "An operation is an object");
            }
            String kind = ScimService.text(op.get("op")).orElse("").toLowerCase(Locale.ROOT);
            if (!List.of("add", "replace", "remove").contains(kind)) {
                throw new ScimError(400, "invalidSyntax", "op is add, replace or remove");
            }
            Object value = kind.equals("remove") ? null : op.get("value");
            Optional<String> path = ScimService.text(op.get("path"));
            if (path.isPresent()) {
                set(user, enterprise, path.get(), value);
            } else if (value instanceof Map<?, ?> values) {
                values.forEach((k, v) -> set(user, enterprise, String.valueOf(k), v));
            } else {
                throw new ScimError(400, "noTarget", "An operation without a path needs an object value");
            }
        }
        return replace(auth, id, user, base);
    }

    @SuppressWarnings("unchecked")
    static void set(Map<String, Object> user, Map<String, Object> enterprise, String path, Object value) {
        String p = path.strip();
        String lower = p.toLowerCase(Locale.ROOT);
        String extension = ScimService.ENTERPRISE.toLowerCase(Locale.ROOT);
        if (lower.equals(extension + ":department")) {
            enterprise.put("department", value);
        } else if (lower.equals(extension) && value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> enterprise.put(String.valueOf(k), v));
        } else if (lower.startsWith("name.")) {
            Map<String, Object> name = new LinkedHashMap<>(user.get("name") instanceof Map<?, ?> n
                    ? (Map<String, Object>) n : Map.of());
            if (!lower.equals("name.formatted")) {
                name.remove("formatted"); // the parts make the name again
            }
            name.put(p.substring(5), value);
            user.put("name", name);
        } else if (lower.startsWith("emails")) {
            if (value instanceof List<?>) {
                user.put("emails", value);
            } else if (value != null) {
                String address = value instanceof Map<?, ?> m ? String.valueOf(m.get("value")) : String.valueOf(value);
                user.put("emails", List.of(Map.of("value", address, "primary", true)));
            }
        } else if (lower.equals("displayname")) {
            user.put("displayName", value);
            if (value != null) {
                user.put("name", Map.of("formatted", value));
            }
        } else if (List.of("active", "username", "externalid", "title", "name").contains(lower)) {
            String key = switch (lower) {
                case "username" -> "userName";
                case "externalid" -> "externalId";
                default -> lower;
            };
            user.put(key, value);
        }
        // other attributes (phoneNumbers, addresses, groups...) mean nothing to CostGuard and are left alone
    }

    /** DELETE: the provider removed the person. They are dismissed; history keeps the employee (ADR 0021). */
    @Transactional
    void delete(ScimTokens.Auth auth, String id) {
        session.bind(auth.tenantId());
        Employee e = reads.employee(id);
        people.dismiss(auth.tenantId(), e.id(), WHO);
        ScimRepository.Link link = scim.link(e.id()).orElse(null);
        scim.save(auth.tenantId(), e.id(), link == null ? null : link.externalId(),
                link == null ? e.email() : link.userName(), false, clock.instant());
    }
}
