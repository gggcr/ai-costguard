package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class SavingsController {

    private final SavingsService service;

    SavingsController(SavingsService service) {
        this.service = service;
    }

    @GetMapping("/cabinet/savings")
    @PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
    @Operation(summary = "What the license pool and purchase checks saved, a month, by month")
    CabinetSavings savings(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "12") int months) {
        return service.savings(TenantClaim.of(jwt), Math.max(1, Math.min(months, 36)));
    }
}
