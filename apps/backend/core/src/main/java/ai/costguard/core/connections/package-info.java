/**
 * Connected systems of a company (ADR 0019): which systems the connector agent pulls, with their non-secret
 * settings, and how each pull went. Owns CONNECTION, CONNECTION_RUN.
 */
@ApplicationModule(displayName = "Connections")
package ai.costguard.core.connections;

import org.springframework.modulith.ApplicationModule;
