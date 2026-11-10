/**
 * Pricing plans (ADR 0037): trial, start and business, with limits on agents, employees, connected systems and
 * gateway operations per month, the end of the trial and read-only access after it, and payment through a
 * provider behind an interface (ADR 0038). Owns TENANT_PLAN, PLAN_SCHEDULE, PLAN_PAYMENT, PLAN_PAYMENT_REF.
 *
 * <p>Other modules ask for room before they create something through {@link ai.costguard.core.plans.PlanLimits}
 * and count their own rows, so this module reads no table of theirs and depends on none of them.
 */
@ApplicationModule(displayName = "Plans")
package ai.costguard.core.plans;

import org.springframework.modulith.ApplicationModule;
