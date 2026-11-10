package ai.costguard.core.imports.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "Imports", description = "Uploads, column mapping and manual review")
class ImportController {

    private final ImportService service;

    ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @UploadsImports
    @Operation(summary = "Upload a file", description = "201 for a new file, 200 if this file was uploaded before.")
    ResponseEntity<ImportJob> upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID vendorId) throws IOException {
        String by = jwt.getClaimAsString("email");
        ImportService.Upload upload = service.upload(TenantClaim.of(jwt),
                file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(), file.getContentType(),
                file.getBytes(), vendorId, by == null ? jwt.getSubject() : by);
        return upload.created()
                ? ResponseEntity.created(URI.create("/api/v1/imports/" + upload.job().id())).body(upload.job())
                : ResponseEntity.ok(upload.job());
    }

    @GetMapping
    @Operation(summary = "List imports, newest first")
    List<ImportJob> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(TenantClaim.of(jwt));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an import with its review tasks")
    ImportJob get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(TenantClaim.of(jwt), id);
    }

    @PutMapping("/{id}/mapping")
    @UploadsImports
    @Operation(summary = "Confirm how columns map to FOCUS fields")
    ImportJob mapping(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody MappingRequest request) {
        return service.confirmMapping(TenantClaim.of(jwt), id, request);
    }

    @PutMapping("/{id}/review-tasks/{taskId}")
    @ManagesImports
    @Operation(summary = "Resolve a value the pipeline was unsure about")
    ImportJob resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID taskId,
            @Valid @RequestBody Resolution resolution) {
        return service.resolve(TenantClaim.of(jwt), id, taskId, resolution.value());
    }
}
