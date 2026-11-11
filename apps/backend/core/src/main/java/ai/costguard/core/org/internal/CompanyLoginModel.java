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
        @NotEmpty @Size(max = 20) List<@Pattern(regexp = "[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") String> domains) {
}

/** What the owner sees: never the secret; {@code redirectUri} goes into the app registration in Entra. */
record CompanyLogin(boolean connected, String provider, String directoryId, String clientId, List<String> domains,
        String redirectUri) {
}

/** The provider to send a person to, by the domain of their work e-mail. */
record LoginHint(String provider, String alias) {
}
