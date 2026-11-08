/**
 * Users, roles and org structure, and the dismissal checklist. Owns TENANT, USER_ACCOUNT, DEPARTMENT, EMPLOYEE.
 * The checklist belongs here because a dismissal starts here: other modules add their part through
 * {@link ai.costguard.core.org.DismissalChecklistSection}, so this module depends on none of them.
 */
@ApplicationModule(displayName = "Organisation")
package ai.costguard.core.org;

import org.springframework.modulith.ApplicationModule;
