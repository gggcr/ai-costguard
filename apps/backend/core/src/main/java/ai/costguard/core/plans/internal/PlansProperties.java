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
            @DefaultValue Limits limits, Map<String, BigDecimal> prices) {

        Plan {
            // prices in other currencies (ADR 0077): the currency code in upper case, the main price among them
            Map<String, BigDecimal> all = new java.util.TreeMap<>();
            if (prices != null) {
                prices.forEach((code, amount) -> all.put(code.toUpperCase(java.util.Locale.ROOT), amount));
            }
            if (monthlyPrice != null) {
                all.put(currency.toUpperCase(java.util.Locale.ROOT), monthlyPrice);
            }
            prices = Map.copyOf(all);
        }

        /** The monthly price in {@code code}; empty when the plan is not sold in it. */
        java.util.Optional<BigDecimal> price(String code) {
            return java.util.Optional.ofNullable(prices.get(code.toUpperCase(java.util.Locale.ROOT)));
        }
    }

    record Limits(Long agents, Long employees, Long connectedSystems, Long operationsPerMonth) {
    }

    /**
     * {@code provider}: empty (no payments: a checkout answers 503), {@code dummy} (payments succeed at once, for
     * the local stack and tests only), {@code stripe} (ADR 0077) or the name of another provider's implementation.
     * {@code returnUrl}: where the provider sends the payer back. {@code grace}: how long after the end of the last
     * paid period a company whose renewal failed keeps working while the provider retries the charge.
     */
    record Payments(@DefaultValue("") String provider, @DefaultValue("http://localhost:3000/settings/plan")
            String returnUrl, @DefaultValue("P7D") Period grace, @DefaultValue Stripe stripe) {
    }

    /**
     * Stripe (ADR 0077). Keys come from the environment only. {@code prices}: the Stripe price id of each plan
     * and currency, keyed {@code <plan>-<currency>} in lower case (start-usd); the amount shown in the catalog must
     * be the amount of that price. {@code tolerance}: how old a signed webhook may be.
     */
    record Stripe(@DefaultValue("") String secretKey, @DefaultValue("") String webhookSecret,
            @DefaultValue("https://api.stripe.com") String apiUrl, @DefaultValue("PT5M") Duration tolerance,
            @DefaultValue("false") boolean automaticTax, Map<String, String> prices) {

        Stripe {
            Map<String, String> ids = new java.util.TreeMap<>();
            if (prices != null) {
                prices.forEach((key, id) -> {
                    if (id != null && !id.isBlank()) {
                        ids.put(key.toLowerCase(java.util.Locale.ROOT), id.strip());
                    }
                });
            }
            prices = Map.copyOf(ids);
        }

        java.util.Optional<String> price(String plan, String currency) {
            return java.util.Optional.ofNullable(prices.get((plan + "-" + currency).toLowerCase(java.util.Locale.ROOT)));
        }

        /** The plan a Stripe price belongs to; empty for a price of something else. */
        java.util.Optional<String> planOf(String priceId) {
            return prices.entrySet().stream().filter(e -> e.getValue().equals(priceId))
                    .map(e -> e.getKey().substring(0, e.getKey().lastIndexOf('-'))).findFirst();
        }
    }

    Plan plan(String name) {
        return catalog.get(name);
    }
}
