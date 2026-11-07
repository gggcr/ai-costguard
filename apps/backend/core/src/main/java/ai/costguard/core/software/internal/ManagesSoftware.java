package ai.costguard.core.software.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** The roles that edit the software section in apps/frontend/src/auth/auth.tsx. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
@interface ManagesSoftware {
}
