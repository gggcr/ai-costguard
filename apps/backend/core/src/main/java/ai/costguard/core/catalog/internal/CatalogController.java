package ai.costguard.core.catalog.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog", description = "Prices of models and paid APIs, Bank of Russia exchange rates")
class CatalogController {

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/prices")
    @Operation(summary = "Prices in force on a date, today by default")
    List<Price> prices(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        return service.current(TenantClaim.of(jwt), date);
    }

    @GetMapping("/prices/{skuId}/history")
    @Operation(summary = "Every price of one SKU, newest first")
    List<Price> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuId) {
        return service.history(TenantClaim.of(jwt), skuId);
    }

    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesPrices
    @Operation(summary = "Set a price from a date", description = "Earlier prices stay for past operations.")
    Price add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewPrice price) {
        return service.add(TenantClaim.of(jwt), price);
    }

    @GetMapping("/fx-rates")
    @Operation(summary = "Bank of Russia rates in force on a date, roubles per unit")
    List<FxRate> rates(@RequestParam(required = false) LocalDate date) {
        return service.rates(date);
    }

    @GetMapping("/fx-conversion")
    @Operation(summary = "Convert an amount to roubles")
    Conversion convert(@RequestParam BigDecimal amount, @RequestParam String currency,
            @RequestParam(required = false) LocalDate date) {
        return service.convert(amount, currency, date);
    }
}
