package ai.costguard.core.org.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** The roles that edit the org section in apps/frontend/src/auth/auth.tsx. Reading stays open to the tenant. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('owner', 'it_admin')")
@interface ManagesOrg {
}
