package ai.costguard.core.org;

import java.util.List;
import java.util.UUID;

/**
 * The enterprise applications of the company's Microsoft Entra ID (ADR 0065): Figma, Adobe, Slack… connected there
 * with single sign-on and user provisioning. Assigning a person gives them the program; removing the assignment
 * makes Entra remove them at the vendor. Needs the app permissions Application.Read.All and
 * AppRoleAssignment.ReadWrite.All.
 */
public interface MicrosoftApps {

    record App(String id, String name) {
    }

    List<App> apps(UUID tenantId);

    void assign(UUID tenantId, String appId, String email);

    /** Nothing to remove is fine. */
    void unassign(UUID tenantId, String appId, String email);

    class Unavailable extends RuntimeException {
        public Unavailable(String what) {
            super(what);
        }
    }
}
