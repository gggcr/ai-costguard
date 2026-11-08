package ai.costguard.gateway.rules;

import ai.costguard.events.CatalogPrice;
import ai.costguard.events.PriceUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Prices from {@code catalog.prices.v1}, per tenant, vendor, model and unit, with their dates. */
@Component
public class PriceBook {

    private final Map<String, List<CatalogPrice>> prices = new ConcurrentHashMap<>();

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

    public Optional<BigDecimal> unitPrice(UUID tenantId, String vendor, String model, PriceUnit unit, LocalDate date) {
        return price(tenantId, vendor, model, unit, date).map(CatalogPrice::getUnitPrice);
    }
}
