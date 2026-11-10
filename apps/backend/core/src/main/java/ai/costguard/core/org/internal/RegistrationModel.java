package ai.costguard.core.org.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * A company registering itself. {@code country}: ISO 3166-1 alpha-2, {@code currency}: ISO 4217. The owner's name
 * is two fields because Keycloak asks for both and would otherwise stop the owner at the first login to fill them.
 */
record RegistrationRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String ownerFirstName,
        @NotBlank @Size(max = 100) String ownerLastName) {
}

/**
 * The same answer whatever happened to the e-mail (new, pending, taken by another company): the reply must not tell
 * a stranger who is registered.
 */
record RegistrationAccepted(String status) {

    static final RegistrationAccepted CHECK_EMAIL = new RegistrationAccepted("check_email");
}

enum InvitationStatus { sent, accepted, revoked }

record Invitation(UUID id, UUID userId, String email, UserRole role, InvitationStatus status, Instant createdAt,
        Instant acceptedAt) {
}

record NewInvitation(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull UserRole role,
        UUID employeeId) {
}
