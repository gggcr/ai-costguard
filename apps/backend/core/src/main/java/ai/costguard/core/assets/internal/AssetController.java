package ai.costguard.core.assets.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@Tag(name = "Assets", description = "Employee equipment: who holds what and when it is due back")
@ApiResponse(responseCode = "400", description = "Invalid request",
        content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = AssetErrorDocs.ProblemBody.class)))
@ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content)
@ApiResponse(responseCode = "403", description = "code: tenant_missing or role_required",
        content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = AssetErrorDocs.ProblemBody.class)))
class AssetController {

    private final AssetService service;

    AssetController(AssetService service) {
        this.service = service;
    }

    /** The tenant comes only from the verified token, never from the request. */
    private static UUID tenant(Jwt jwt) {
        String claim = jwt.getClaimAsString("tenant_id");
        if (claim == null) {
            throw AssetProblem.TENANT_MISSING.failure();
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            throw AssetProblem.TENANT_MISSING.failure();
        }
    }

    @PostMapping("/assets")
    @ManagesAssets
    @Operation(summary = "Register an asset")
    @AssetErrorDocs.InventoryNumberConflict
    ResponseEntity<Asset> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewAsset asset) {
        Asset created = service.create(tenant(jwt), asset);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + created.id())).body(created);
    }

    @GetMapping("/assets")
    @Operation(summary = "List assets, optionally by status")
    AssetPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) AssetStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.list(tenant(jwt), status, page, size);
    }

    @GetMapping("/assets/{id}")
    @Operation(summary = "Get an asset")
    @AssetErrorDocs.NotFound
    Asset get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenant(jwt), id);
    }

    @PutMapping("/assets/{id}/assignment")
    @ManagesAssets
    @Operation(summary = "Issue an asset to an employee or move its return date", description = "Idempotent.")
    @AssetErrorDocs.NotFound
    @AssetErrorDocs.AssetStateConflict
    Assignment assign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody NewAssignment request) {
        return service.assign(tenant(jwt), id, request);
    }

    @DeleteMapping("/assets/{id}/assignment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesAssets
    @Operation(summary = "Take an asset back from its holder", description = "Idempotent.")
    @AssetErrorDocs.NotFound
    void takeBack(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.takeBack(tenant(jwt), id);
    }

    @GetMapping("/assets/{id}/assignments")
    @Operation(summary = "Assignment history, newest first")
    @AssetErrorDocs.NotFound
    List<Assignment> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.history(tenant(jwt), id);
    }

    @PutMapping("/assets/{id}/retirement")
    @ManagesAssets
    @Operation(summary = "Retire an asset that nobody holds", description = "Idempotent.")
    @AssetErrorDocs.NotFound
    @AssetErrorDocs.AssetStateConflict
    Asset retire(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.retire(tenant(jwt), id);
    }

    @GetMapping("/employees/{employeeId}/assets")
    @Operation(summary = "What an employee still holds, with its total cost")
    EmployeeAssets heldBy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID employeeId) {
        return service.heldBy(tenant(jwt), employeeId);
    }
}
