package ai.costguard.core.ledger.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Ledger", description = "Budgets and the journal of entries")
class LedgerController {

    private final LedgerService service;

    LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List budgets of all levels")
    List<Budget> budgets(@AuthenticationPrincipal Jwt jwt) {
        return service.budgets(TenantClaim.of(jwt));
    }

    @PostMapping
    @ManagesBudgets
    @Operation(summary = "Create a budget")
    ResponseEntity<Budget> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewBudget request) {
        Budget created = service.create(TenantClaim.of(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/budgets/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a budget")
    Budget budget(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.budget(TenantClaim.of(jwt), id);
    }

    @PutMapping("/{id}/limit")
    @ManagesBudgets
    @Operation(summary = "Change a limit", description = "A limit below the spent amount is allowed.")
    Budget setLimit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody LimitChange change) {
        return service.setLimit(TenantClaim.of(jwt), id, change.limit());
    }

    @GetMapping("/{id}/entries")
    @Operation(summary = "Entries of a budget, newest first")
    LedgerPage entries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.entries(TenantClaim.of(jwt), id, page, size);
    }
}
