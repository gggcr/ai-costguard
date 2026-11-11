package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Запрос на покупку ПО (ADR 0056). Finance (owner, finance) decides what is not answered by the pool. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class PurchaseController {

    private final PurchaseService service;

    PurchaseController(PurchaseService service) {
        this.service = service;
    }

    @PostMapping(path = "/me/purchase-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask to buy a program, a subscription or an API, with the invoice or receipt")
    PurchaseRequest ask(@AuthenticationPrincipal Jwt jwt, @RequestParam String product,
            @RequestParam(required = false) String vendor, @RequestParam BigDecimal amount,
            @RequestParam(required = false) String currency, @RequestParam(defaultValue = "month") PurchasePeriod period,
            @RequestParam(required = false) String reason, @RequestPart(name = "file", required = false) MultipartFile file) {
        if (amount.signum() < 0) {
            throw CabinetProblem.PURCHASE_AMOUNT.failure();
        }
        byte[] content = null;
        String name = null;
        if (file != null && !file.isEmpty()) {
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            name = file.getOriginalFilename() == null ? "счёт" : file.getOriginalFilename();
        }
        return service.ask(TenantClaim.of(jwt), jwt, new PurchaseService.Ask(product, vendor, amount,
                currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT), period, reason, name, content));
    }

    @GetMapping("/me/purchase-requests")
    @Operation(summary = "My purchase requests")
    List<PurchaseRequest> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @DeleteMapping("/me/purchase-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel my request that waits for finance")
    void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancel(TenantClaim.of(jwt), jwt, id);
    }

    @GetMapping("/me/purchase-requests/{id}/file")
    @Operation(summary = "The invoice or receipt of my request")
    ResponseEntity<byte[]> myFile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return download(service.myFile(TenantClaim.of(jwt), jwt, id));
    }

    @GetMapping("/purchase-requests")
    @PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
    @Operation(summary = "Every purchase request of the company")
    List<PurchaseRequest> all(@AuthenticationPrincipal Jwt jwt) {
        return service.all(TenantClaim.of(jwt));
    }

    @GetMapping("/purchase-requests/{id}/file")
    @PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
    @Operation(summary = "The invoice or receipt of a request")
    ResponseEntity<byte[]> file(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return download(service.file(TenantClaim.of(jwt), id));
    }

    @PutMapping("/purchase-requests/{id}/decision")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Approve or reject a purchase; the employee and the head of the department are told")
    PurchaseRequest decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody PurchaseDecision decision) {
        return service.decide(TenantClaim.of(jwt), id, decision, Whoami.who(jwt));
    }

    private static ResponseEntity<byte[]> download(DocumentFile f) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(f.content());
    }
}
