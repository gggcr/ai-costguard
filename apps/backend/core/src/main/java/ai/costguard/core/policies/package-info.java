/**
 * Company rules on top of agent mandates, their simulation, and publication of snapshots to the gateway.
 * Owns POLICY. A rule names the operations it catches; DENY wins over REQUIRE_APPROVAL.
 */
@ApplicationModule(displayName = "Policies")
package ai.costguard.core.policies;

import org.springframework.modulith.ApplicationModule;
