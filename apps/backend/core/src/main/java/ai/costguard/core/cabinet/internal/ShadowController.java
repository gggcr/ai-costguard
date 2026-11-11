package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Теневые траты (ADR 0057). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class ShadowController {

    private final ShadowService service;

    ShadowController(ShadowService service) {
        this.service = service;
    }

    @PostMapping("/me/shadow-spend")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report software I paid for with my own card; management sees only aggregates")
    ShadowSpend report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewShadowSpend request) {
        return service.report(TenantClaim.of(jwt), jwt, request);
    }

    @PostMapping(path = "/me/shadow-spend/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Report several purchases from .xlsx or .csv: сервис, сумма, дата, назначение")
    ShadowImport load(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        try {
            return service.load(TenantClaim.of(jwt), jwt, file.getOriginalFilename() == null ? "траты.csv"
                    : file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/me/shadow-spend")
    @Operation(summary = "My reports")
    List<ShadowSpend> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @DeleteMapping("/me/shadow-spend/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of my reports")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(TenantClaim.of(jwt), jwt, id);
    }

    @GetMapping("/shadow-spend/summary")
    @PreAuthorize("hasAnyRole('owner', 'finance', 'it_admin')")
    @Operation(summary = "What people pay for themselves, by service; only where at least 3 people reported")
    ShadowSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return service.summary(TenantClaim.of(jwt));
    }
}
