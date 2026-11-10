package ai.costguard.core.plans.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code costguard.plans} (ADR 0037). The catalog and its limits come from the configuration only; any value can
 * be overridden from the environment, e.g. {@code COSTGUARD_PLANS_CATALOG_START_LIMITS_AGENTS=20}.
 *
 * @param trialLength how long a new company's trial lasts
 * @param warnBefore how long before the end of the trial or the paid period the owners are warned
 * @param paidPeriod what one payment buys
 * @param watchDelay how often the watcher looks for periods that end
 * @param catalog the plans by name: trial, start, business
 * @param payments the payment provider (ADR 0038)
 */
@ConfigurationProperties("costguard.plans")
record PlansProperties(
        @DefaultValue("P14D") Period trialLength,
        @DefaultValue("P3D") Period warnBefore,
        @DefaultValue("P1M") Period paidPeriod,
        @DefaultValue("PT15M") Duration watchDelay,
        Map<String, Plan> catalog,
        @DefaultValue Payments payments) {

    PlansProperties {
        catalog = catalog == null ? Map.of() : Map.copyOf(catalog);
        for (String name : PlanName.ALL) {
            if (!catalog.containsKey(name)) {
                throw new IllegalStateException("costguard.plans.catalog." + name + " is not configured");
            }
        }
    }

    /**
     * One plan. A limit left empty is no limit. {@code monthlyPrice} is what one paid period costs; the trial
     * has none.
     */
    record Plan(String title, BigDecimal monthlyPrice, @DefaultValue("RUB") String currency,
            @DefaultValue Limits limits) {
    }

    record Limits(Long agents, Long employees, Long connectedSystems, Long operationsPerMonth) {
    }

    /**
     * {@code provider}: empty (no payments: a checkout answers 503), {@code dummy} (payments succeed at once, for
     * the local stack and tests only) or the name of a real provider's implementation. {@code returnUrl}: where the
     * provider sends the payer back.
     */
    record Payments(@DefaultValue("") String provider, @DefaultValue("http://localhost:3000/settings/plan")
            String returnUrl) {
    }

    Plan plan(String name) {
        return catalog.get(name);
    }
}
