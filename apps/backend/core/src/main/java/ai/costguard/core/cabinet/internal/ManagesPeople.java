package ai.costguard.core.cabinet.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** Employer-side work on employees' documents, vacations and pay: the roles that run the company's people side. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
@interface ManagesPeople {
}
