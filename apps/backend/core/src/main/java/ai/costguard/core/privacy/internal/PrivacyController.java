package ai.costguard.core.privacy.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Privacy", description = "Erasing an employee's personal data, erasing the company, and the records of both")
class PrivacyController {

    private final PrivacyService service;

    PrivacyController(PrivacyService service) {
        this.service = service;
    }

    @PostMapping("/employees/{id}/erasure")
    @ErasesData
    @Operation(operationId = "eraseEmployee", summary = "Erase a dismissed employee's personal data",
            description = "Deletes or anonymizes what every store holds about the person and returns the record of "
                    + "it. Handover acts and uploaded files stay until their accounting term. Asking again returns "
                    + "the first record.")
    ResponseEntity<Erasure> eraseEmployee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        PrivacyService.Erased result = service.eraseEmployee(TenantClaim.of(jwt), id);
        Erasure e = result.erasure();
        return result.created() ? ResponseEntity.created(URI.create("/api/v1/erasures/" + e.id())).body(e)
                : ResponseEntity.ok(e);
    }

    @GetMapping("/employees/{id}/personal-data")
    @ErasesData
    @Operation(operationId = "exportEmployee", summary = "Export an employee's personal data",
            description = "Owner only. Everything every module holds about the person, table by table, as stored "
                    + "(machine-readable), for a request of access or portability (GDPR art. 15 and 20, CCPA). "
                    + "Secrets and file contents are left out. The export is recorded in the activity log.")
    java.util.Map<String, Object> exportEmployee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.exportEmployee(TenantClaim.of(jwt), id);
    }

    @GetMapping("/erasures")
    @ReadsErasures
    @Operation(operationId = "listErasures", summary = "The latest 200 erasures of employees")
    List<Erasure> erasures(@AuthenticationPrincipal Jwt jwt) {
        return service.erasures(TenantClaim.of(jwt));
    }

    @GetMapping("/erasures/{id}")
    @ReadsErasures
    @Operation(operationId = "getErasure", summary = "What one erasure deleted, anonymized and kept")
    Erasure erasure(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.erasure(TenantClaim.of(jwt), id);
    }

    @PostMapping("/company/erasure")
    @ErasesData
    @Operation(operationId = "requestCompanyErasure", summary = "Erase the company when it leaves",
            description = "Everything of the company is erased after the grace period (30 days by default); until "
                    + "then the owner can cancel. The company name, exactly as saved, confirms the request.")
    ResponseEntity<CompanyErasure> requestCompanyErasure(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CompanyErasureRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.requestCompanyErasure(TenantClaim.of(jwt), request.confirmName()));
    }

    @GetMapping("/company/erasure")
    @ReadsErasures
    @Operation(operationId = "getCompanyErasure", summary = "Whether the company is to be erased, and when")
    CompanyErasure companyErasure(@AuthenticationPrincipal Jwt jwt) {
        return service.companyErasure(TenantClaim.of(jwt));
    }

    @DeleteMapping("/company/erasure")
    @ErasesData
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelCompanyErasure", summary = "Cancel erasing the company",
            description = "Only while the grace period lasts.")
    void cancelCompanyErasure(@AuthenticationPrincipal Jwt jwt) {
        service.cancelCompanyErasure(TenantClaim.of(jwt));
    }
}
