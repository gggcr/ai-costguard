package ai.costguard.core.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Who signed in to what through the company's Microsoft Entra ID (ADR 0063): the sign-in log of Microsoft 365 and
 * of every app with single sign-on through it. Empty for a company without a connected app or its permission.
 */
public interface MicrosoftSignIns {

    record SignIn(String email, String app, LocalDate day) {
    }

    /** Companies whose Microsoft app the core can call. */
    List<UUID> tenants();

    /** The last sign-in of each person to each app in the last {@code days} days. */
    List<SignIn> recent(UUID tenantId, int days);

    class Unavailable extends RuntimeException {
        public Unavailable(String what) {
            super(what);
        }
    }
}
