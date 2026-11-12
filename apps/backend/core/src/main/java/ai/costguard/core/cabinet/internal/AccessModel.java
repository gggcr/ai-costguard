package ai.costguard.core.cabinet.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How a seat is given and taken back at the vendor: {@code kind} entra (an enterprise app of the company's Entra ID)
 * or scim (the vendor's SCIM 2.0 API: base URL and a token, needed the first time); {@code manual} removes it.
 */
record AccessSettings(
        @NotNull @Pattern(regexp = "entra|scim|manual") String kind,
        @Size(max = 100) String entraAppId,
        @Size(max = 300) String entraAppName,
        @Pattern(regexp = "https://\\S+") @Size(max = 500) String scimUrl,
        @Size(max = 4000) String scimToken,
        @Pattern(regexp = "deactivate|delete") String scimRevoke) {
}

/** Never the token. */
record SubscriptionAccess(String kind, String entraAppId, String entraAppName, String scimUrl, boolean scimTokenSet,
        String scimRevoke, List<AccessChange> recent) {
}

record AccessChange(UUID id, UUID employeeId, String employeeName, String action, String status, String detail,
        Instant at) {
}

record EnterpriseApp(String id, String name) {
}
