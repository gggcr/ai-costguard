package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.core.software.LicensePool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Пул лицензий (ADR 0055). IT is owner or it_admin, as for software. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class LicenseController {

    private final LicenseService service;

    private final MicrosoftActivity activity;

    private final LeaseService leases;

    LicenseController(LicenseService service, MicrosoftActivity activity, LeaseService leases) {
        this.service = service;
        this.activity = activity;
        this.leases = leases;
    }

    @PostMapping("/me/licenses/{licenseId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Give my license back: to the pool, or to the colleague it was lent from (ADR 0066)")
    void release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID licenseId) {
        leases.release(TenantClaim.of(jwt), jwt, licenseId);
    }

    @GetMapping("/me/licenses")
    @Operation(summary = "My licenses, the company's pool with what is free or idle, and my requests")
    MyLicenses mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @PostMapping("/me/license-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask for a license: a free seat is given at once, otherwise IT decides")
    LicenseRequest ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewLicenseRequest request) {
        return service.ask(TenantClaim.of(jwt), jwt, request);
    }

    @DeleteMapping("/me/license-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel my request that waits for IT")
    void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancel(TenantClaim.of(jwt), jwt, id);
    }

    @GetMapping("/license-requests")
    @PreAuthorize("hasAnyRole('owner', 'it_admin', 'finance')")
    @Operation(summary = "Every license request of the company")
    List<LicenseRequest> all(@AuthenticationPrincipal Jwt jwt) {
        return service.all(TenantClaim.of(jwt));
    }

    @GetMapping("/license-pool")
    @PreAuthorize("hasAnyRole('owner', 'it_admin', 'finance')")
    @Operation(summary = "Seats, free seats and idle licenses of every subscription")
    List<LicensePool.Offer> pool(@AuthenticationPrincipal Jwt jwt) {
        return service.overview(TenantClaim.of(jwt));
    }

    @PutMapping("/license-requests/{id}/decision")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "Approve (an idle license goes back to the pool first) or reject a request")
    LicenseRequest decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody LicenseDecision decision) {
        return service.decide(TenantClaim.of(jwt), id, decision, Whoami.who(jwt));
    }

    @PostMapping("/license-pool/microsoft-activity")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "Mark licenses used from the Microsoft sign-in log of the last days (also every morning)")
    MicrosoftActivityResult microsoftActivity(@AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int days) {
        try {
            return activity.load(TenantClaim.of(jwt), Math.max(1, Math.min(days, 30)));
        } catch (ai.costguard.core.org.MicrosoftSignIns.Unavailable e) {
            throw CabinetProblem.MICROSOFT_UNAVAILABLE.failure();
        }
    }

    @PostMapping("/license-pool/reclaim")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "Take every idle license back into the pool; the holders are told")
    Reclaimed reclaim(@AuthenticationPrincipal Jwt jwt) {
        return service.reclaimIdle(TenantClaim.of(jwt));
    }
}
