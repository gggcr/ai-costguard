package ai.costguard.core.analytics.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Spending and savings for the dashboard")
class AnalyticsController {

    private final AnalyticsService service;
    private final ForecastService forecasts;
    private final ScenarioService scenarios;

    AnalyticsController(AnalyticsService service, ForecastService forecasts, ScenarioService scenarios) {
        this.service = service;
        this.forecasts = forecasts;
        this.scenarios = scenarios;
    }

    @GetMapping("/summary")
    @Operation(summary = "Agent spend per month and findings totals")
    AnalyticsService.Summary summary(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
        return service.summary(TenantClaim.of(jwt), months);
    }

    @GetMapping("/forecast")
    @Operation(summary = "The newest forecast: the company, its departments, the registry baseline and savings",
            description = "Published by the forecast job (costguard-forecast); 404 forecast_not_found before the first.")
    PublishedForecast forecast(@AuthenticationPrincipal Jwt jwt) {
        return forecasts.newest(TenantClaim.of(jwt));
    }

    @PutMapping("/forecast")
    @PublishesForecasts
    @Operation(summary = "Publish a forecast",
            description = "The departments must add up to the company within a kopeck each, every series must have "
                    + "horizon points: else 400 forecast_does_not_add_up. Older forecasts are kept.")
    PublishedForecast publish(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ForecastDocument document) {
        return forecasts.publish(TenantClaim.of(jwt), document);
    }

    @PostMapping("/scenarios")
    @Operation(summary = "What if: seats, plans, yearly billing, dropping a subscription",
            description = "Computes, changes nothing. Monthly costs of the registry before and after, a yearly price "
                    + "as a twelfth; over the months of the newest forecast, with the difference taken off it.")
    ScenarioResult scenario(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScenarioRequest request) {
        return scenarios.run(TenantClaim.of(jwt), request);
    }
}
