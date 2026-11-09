/**
 * The activity log (ADR 0022): who changed what and when, in budgets, mandates, roles, connections, equipment and
 * erasures. Entries are only added; the database refuses to change or delete one, except for the retention job and
 * the erasure of a whole company. Owns ACTIVITY_ACTOR, ACTIVITY_ENTRY.
 *
 * <p>Modules publish {@link ai.costguard.core.shared.activity.ActionTaken} in the transaction of the change; the
 * log writes the entry in that same transaction.
 */
@ApplicationModule(displayName = "Activity log")
package ai.costguard.core.activity;

import org.springframework.modulith.ApplicationModule;
