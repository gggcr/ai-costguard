/** Shared kernel open to every module: the outbox and core-side adapters. Tenant context, RLS and RFC 9457 errors come from platform-starter. */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package ai.costguard.core.shared;

import org.springframework.modulith.ApplicationModule;
