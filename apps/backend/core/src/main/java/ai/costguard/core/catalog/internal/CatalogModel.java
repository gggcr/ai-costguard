package ai.costguard.core.catalog.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

enum PriceUnit { INPUT_TOKEN, OUTPUT_TOKEN, REQUEST }

record Price(UUID id, String skuId, String vendor, String model, PriceUnit unit, BigDecimal unitPrice,
        String currency, LocalDate validFrom) {
}

record NewPrice(
        @NotBlank String skuId,
        @NotBlank String vendor,
        String model,
        @NotNull PriceUnit unit,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 9) BigDecimal unitPrice,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull LocalDate validFrom) {
}

record FxRate(LocalDate date, String currency, BigDecimal rate) {
}

record Conversion(BigDecimal amount, String currency, LocalDate date, BigDecimal rub) {
}
