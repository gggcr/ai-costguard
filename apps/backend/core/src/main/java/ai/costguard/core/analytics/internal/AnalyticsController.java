package ai.costguard.core.analytics.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Spending and savings for the dashboard")
class AnalyticsController {

    private final AnalyticsService service;

    AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @Operation(summary = "Agent spend per month and findings totals")
    AnalyticsService.Summary summary(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
        return service.summary(TenantClaim.of(jwt), months);
    }
}
