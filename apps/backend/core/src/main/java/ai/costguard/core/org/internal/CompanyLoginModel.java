package ai.costguard.core.org.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The company's Microsoft Entra ID (ADR 0058). {@code directoryId}: the tenant (directory) id or a verified domain
 * of it; {@code clientSecret} is needed the first time and kept by Keycloak after; {@code domains}: the e-mail domains
 * whose people log in through it.
 */
record CompanyLoginSettings(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9.-]{3,100}") String directoryId,
        @NotBlank @Size(max = 100) String clientId,
        @Size(max = 500) String clientSecret,
        @NotEmpty @Size(max = 20) List<@Pattern(regexp = "[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") String> domains,
        Boolean createMailboxes,
        @Pattern(regexp = "[0-9a-fA-F-]{36}") String licenseSkuId,
        @Pattern(regexp = "[A-Za-z]{2}") String usageLocation) {
}

/** What the owner sees: never the secret; {@code redirectUri} goes into the app registration in Entra. */
record CompanyLogin(boolean connected, String provider, String directoryId, String clientId, List<String> domains,
        String redirectUri, boolean createMailboxes, String licenseSkuId, String usageLocation) {
}

/** Adding an employee (ADR 0059): with {@code createMailbox} the address is made in Microsoft 365, else typed. */
record NewEmployee(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @jakarta.validation.constraints.NotNull java.util.UUID departmentId,
        @NotBlank String position,
        @jakarta.validation.constraints.Email String email,
        boolean createMailbox) {
}

/** Shown once: the address and the password to give the person, e.g. with the laptop. */
record Onboarded(Employee employee, String email, String password, boolean mailboxCreated) {
}

/** The person's own way of logging in. */
record MySignIn(boolean otp, boolean otpFirst, String setupUrl) {
}

/** The provider to send a person to, by the domain of their work e-mail. */
record LoginHint(String provider, String alias) {
}
