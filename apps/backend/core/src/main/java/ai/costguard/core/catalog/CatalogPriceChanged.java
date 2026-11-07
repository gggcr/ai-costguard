package ai.costguard.core.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A price the gateway uses to estimate cost. */
public record CatalogPriceChanged(UUID tenantId, String skuId, String vendor, String model, String unit,
        BigDecimal unitPrice, String currency, LocalDate validFrom) {
}
