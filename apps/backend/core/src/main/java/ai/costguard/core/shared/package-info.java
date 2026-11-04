/** Shared kernel available to every module: tenant and RLS, outbox, RFC 9457 errors. */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package ai.costguard.core.shared;

import org.springframework.modulith.ApplicationModule;
