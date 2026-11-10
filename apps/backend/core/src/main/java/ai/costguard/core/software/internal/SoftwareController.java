package ai.costguard.core.software.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Software", description = "Vendors, contracts, subscriptions and licenses")
class SoftwareController {

    private final SoftwareService service;

    SoftwareController(SoftwareService service) {
        this.service = service;
    }

    @GetMapping("/vendors")
    @Operation(summary = "List vendors")
    List<Vendor> vendors(@AuthenticationPrincipal Jwt jwt) {
        return service.vendors(TenantClaim.of(jwt));
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesSoftware
    @Operation(summary = "Add a vendor")
    Vendor createVendor(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VendorDetails details) {
        return service.createVendor(TenantClaim.of(jwt), details);
    }

    @PutMapping("/vendors/{id}")
    @ManagesSoftware
    @Operation(summary = "Change a vendor")
    Vendor updateVendor(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody VendorDetails details) {
        return service.updateVendor(TenantClaim.of(jwt), id, details);
    }

    @GetMapping("/contracts")
    @Operation(summary = "List contracts, soonest end first")
    List<Contract> contracts(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID vendorId) {
        return service.contracts(TenantClaim.of(jwt), vendorId);
    }

    @PostMapping("/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesSoftware
    @Operation(summary = "Add a contract")
    Contract createContract(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ContractDetails details) {
        return service.createContract(TenantClaim.of(jwt), details);
    }

    @PutMapping("/contracts/{id}")
    @ManagesSoftware
    @Operation(summary = "Change a contract")
    Contract updateContract(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ContractDetails details) {
        return service.updateContract(TenantClaim.of(jwt), id, details);
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "List subscriptions with assigned seats")
    List<Subscription> subscriptions(@AuthenticationPrincipal Jwt jwt) {
        return service.subscriptions(TenantClaim.of(jwt));
    }

    @GetMapping("/subscriptions/{id}")
    @Operation(summary = "Get a subscription")
    Subscription subscription(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.subscription(TenantClaim.of(jwt), id);
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesSoftware
    @Operation(summary = "Add a subscription")
    Subscription createSubscription(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SubscriptionDetails details) {
        return service.createSubscription(TenantClaim.of(jwt), details);
    }

    @PutMapping("/subscriptions/{id}")
    @ManagesSoftware
    @Operation(summary = "Change a subscription", description = "Seats cannot drop below assigned licenses.")
    Subscription updateSubscription(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SubscriptionDetails details) {
        return service.updateSubscription(TenantClaim.of(jwt), id, details);
    }

    @GetMapping("/subscriptions/{id}/licenses")
    @Operation(summary = "Who holds a license of a subscription")
    List<License> licenses(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.licenses(TenantClaim.of(jwt), id);
    }

    @PostMapping("/subscriptions/{id}/licenses")
    @ManagesSoftware
    @Operation(summary = "Give an employee a license", description = "Idempotent.")
    License assign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody NewLicense request) {
        return service.assign(TenantClaim.of(jwt), id, request.employeeId());
    }

    @DeleteMapping("/licenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesSoftware
    @Operation(summary = "Take a license back")
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.revoke(TenantClaim.of(jwt), id);
    }

    @PutMapping("/licenses/{id}/activity")
    @ManagesSoftware
    @Operation(summary = "Record the last login", description = "Only moves forward.")
    License activity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Activity activity) {
        return service.recordActivity(TenantClaim.of(jwt), id, activity);
    }

    @GetMapping("/invoices")
    @Operation(summary = "List invoices, newest first",
            description = "From the ingest pipeline and the connectors; at most 500. paymentId is the payment that "
                    + "settled the invoice.")
    List<Invoice> invoices(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID subscriptionId) {
        return service.invoices(TenantClaim.of(jwt), vendorId, subscriptionId);
    }

    @GetMapping("/payments")
    @Operation(summary = "List payments, newest first", description = "From bank statements and banks; at most 500.")
    List<Payment> payments(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID vendorId) {
        return service.payments(TenantClaim.of(jwt), vendorId);
    }

    @GetMapping("/plans")
    @Operation(summary = "List the plans products are sold on",
            description = "The audit compares a subscription's plan with the other plans of its product.")
    List<Plan> plans(@AuthenticationPrincipal Jwt jwt) {
        return service.plans(TenantClaim.of(jwt));
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesSoftware
    @Operation(summary = "Add a plan of a product")
    Plan createPlan(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlanDetails details) {
        return service.createPlan(TenantClaim.of(jwt), details);
    }

    @PutMapping("/plans/{id}")
    @ManagesSoftware
    @Operation(summary = "Change a plan")
    Plan updatePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PlanDetails details) {
        return service.updatePlan(TenantClaim.of(jwt), id, details);
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesSoftware
    @Operation(summary = "Remove a plan")
    void deletePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deletePlan(TenantClaim.of(jwt), id);
    }
}
