package ai.costguard.gateway.rules;

import ai.costguard.events.CatalogPrice;
import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.budget.BudgetTree;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Prices from {@code catalog.prices.v1}, per tenant, vendor, model and unit, with their dates. A price is used in the
 * currency of the company's budgets (ADR 0072): a dollar price of a company that keeps roubles is converted at the
 * latest rate of {@code catalog.rates.v1}, and a price with no rate is no price, which the ladder refuses rather
 * than charge a dollar as a rouble.
 */
@Component
public class PriceBook {

    private final Map<String, List<CatalogPrice>> prices = new ConcurrentHashMap<>();
    private final RateBook rates;
    private final BudgetTree budgets;

    /** A book without rates or budgets: every price stays in its own currency, as in a test of one currency. */
    public PriceBook() {
        this(new RateBook(), new BudgetTree());
    }

    @Autowired
    public PriceBook(RateBook rates, BudgetTree budgets) {
        this.rates = rates;
        this.budgets = budgets;
    }

    private static String key(UUID tenantId, String vendor, String model, PriceUnit unit) {
        return tenantId + "|" + vendor.toLowerCase() + "|" + (model == null ? "" : model.toLowerCase()) + "|" + unit;
    }

    public void apply(CatalogPrice price) {
        List<CatalogPrice> list = prices.computeIfAbsent(
                key(price.getTenantId(), price.getProvider(), price.getModel(), price.getUnit()),
                k -> new CopyOnWriteArrayList<>());
        list.removeIf(p -> p.getSkuId().equals(price.getSkuId()) && p.getValidFrom().equals(price.getValidFrom()));
        list.add(price);
    }

    /** The price in force on {@code date}, in its own currency. */
    public Optional<CatalogPrice> price(UUID tenantId, String vendor, String model, PriceUnit unit, LocalDate date) {
        return prices.getOrDefault(key(tenantId, vendor, model, unit), List.of()).stream()
                .filter(p -> !p.getValidFrom().isAfter(date))
                .max(Comparator.comparing(CatalogPrice::getValidFrom));
    }

    /** The price of one unit in force on {@code date}, in the currency of the company's budgets. */
    public Optional<BigDecimal> unitPrice(UUID tenantId, String vendor, String model, PriceUnit unit, LocalDate date) {
        return price(tenantId, vendor, model, unit, date)
                .flatMap(p -> inCompanyCurrency(tenantId, exact(p), p.getCurrency()));
    }

    /**
     * {@code amount} of {@code currency} in the currency of the company's budgets; as it is while the gateway knows
     * no budget of the company, which then has nothing to charge anyway.
     */
    public Optional<BigDecimal> inCompanyCurrency(UUID tenantId, BigDecimal amount, String currency) {
        Optional<String> company = budgets.currency(tenantId);
        return company.isEmpty() ? Optional.of(amount) : rates.convert(amount, currency, company.get());
    }

    /** The nine decimals the catalog keeps, where the core sends them: six make $0.15 per million tokens zero. */
    private static BigDecimal exact(CatalogPrice p) {
        return p.getUnitPriceExact() != null ? p.getUnitPriceExact() : p.getUnitPrice();
    }
}
