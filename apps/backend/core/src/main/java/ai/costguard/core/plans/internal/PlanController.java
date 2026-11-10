package ai.costguard.core.plans.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Plans", description = "Pricing plans, their limits, the trial and payment")
class PlanController {

    private final PlanService service;

    PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/billing/plans")
    @Operation(summary = "List the plans and their limits", description = "An empty limit is no limit.")
    List<PlanOffer> catalog() {
        return service.catalog();
    }

    @GetMapping("/api/v1/billing/plan")
    @Operation(summary = "Get the company's plan", description = "status read_only: the trial or the paid period "
            + "ended and changes are refused with 402 plan_read_only until a plan is paid. 404 plan_not_assigned: "
            + "the company existed before plans and has no limits.")
    ResponseEntity<CurrentPlan> current(@AuthenticationPrincipal Jwt jwt) {
        return service.current(TenantClaim.of(jwt)).map(ResponseEntity::ok)
                .orElseThrow(PlanProblem.PLAN_NOT_ASSIGNED::failure);
    }

    @PostMapping("/api/v1/billing/checkout")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Pay for a plan", description = "Owner only. Works when the company is read-only too. "
            + "status pending: send the payer to confirmationUrl, the plan changes when the provider confirms; "
            + "succeeded: the plan changed already. 503 payment_provider_missing: payments are not configured.")
    CheckoutView checkout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckoutRequest request) {
        return service.checkout(TenantClaim.of(jwt), request.plan());
    }

    @PostMapping("/api/v1/billing/notifications/{provider}")
    @SecurityRequirements
    @Operation(summary = "Receive a notice from the payment provider", description = "Called by the provider, "
            + "not by people: no token. The provider's implementation verifies the notice; one it cannot verify "
            + "changes nothing (400 payment_notice_invalid).")
    ResponseEntity<Void> notice(@PathVariable String provider, @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) String body) {
        // Header names in lower case: a provider's implementation must not guess how they were written.
        Map<String, String> named = new java.util.HashMap<>();
        headers.forEach((k, v) -> named.put(k.toLowerCase(java.util.Locale.ROOT), v));
        service.notice(provider, Map.copyOf(named), body == null ? "" : body);
        return ResponseEntity.ok().build();
    }
}
