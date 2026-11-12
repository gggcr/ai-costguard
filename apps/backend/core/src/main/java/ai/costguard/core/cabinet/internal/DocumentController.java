package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.RequestBody;

/** Employee documents: the employer adds them, the employee reads their own (ADR 0052). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class DocumentController {

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/me/documents")
    @Operation(summary = "My documents: contract, agreements, orders and handover acts")
    List<EmployeeDocument> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @GetMapping("/me/documents/{id}/file")
    @Operation(summary = "The file of one of my documents")
    ResponseEntity<byte[]> myFile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return download(service.myFile(TenantClaim.of(jwt), jwt, id));
    }

    @PostMapping(path = "/employees/{employeeId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesPeople
    @Operation(summary = "Add a document of an employee; the employee is notified")
    EmployeeDocument add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID employeeId,
            @RequestPart("file") MultipartFile file, @RequestParam DocumentKind kind, @RequestParam String title,
            @RequestParam(required = false) String number, @RequestParam(required = false) LocalDate signedOn,
            @RequestParam(required = false) List<SignerParty> signers) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "document"
                : file.getOriginalFilename();
        String type = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        return service.add(TenantClaim.of(jwt), employeeId,
                new DocumentService.Upload(kind, title, number, signedOn, name, type, content, signers),
                Whoami.who(jwt));
    }

    @PostMapping("/me/documents/{id}/signature")
    @Operation(summary = "Sign my document with a code of my signing key (ADR 0068)")
    EmployeeDocument signMine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SignRequest request) {
        return service.signAsEmployee(TenantClaim.of(jwt), jwt, id, request.code());
    }

    @PostMapping("/documents/{id}/signature/employer")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Sign an employee's document as the employer")
    EmployeeDocument signAsEmployer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SignRequest request) {
        return service.signAs(TenantClaim.of(jwt), jwt, id, SignerParty.employer, request.code());
    }

    @PostMapping("/documents/{id}/signature/accountant")
    @PreAuthorize("hasRole('finance')")
    @Operation(summary = "Sign an employee's document as the accountant")
    EmployeeDocument signAsAccountant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SignRequest request) {
        return service.signAs(TenantClaim.of(jwt), jwt, id, SignerParty.accountant, request.code());
    }

    @GetMapping("/documents/awaiting-signature")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Documents I have to sign: as the employer (owner) or the accountant (finance)")
    List<AwaitingSignature> awaiting(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        List<SignerParty> parties = new ArrayList<>();
        if (roles.contains("ROLE_owner")) {
            parties.add(SignerParty.employer);
        }
        if (roles.contains("ROLE_finance")) {
            parties.add(SignerParty.accountant);
        }
        return service.awaiting(TenantClaim.of((Jwt) auth.getPrincipal()), parties);
    }

    @GetMapping("/employees/{employeeId}/documents")
    @ManagesPeople
    @Operation(summary = "Documents of an employee")
    List<EmployeeDocument> of(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID employeeId) {
        return service.of(TenantClaim.of(jwt), employeeId);
    }

    @GetMapping("/employees/{employeeId}/documents/{id}/file")
    @ManagesPeople
    @Operation(summary = "The file of an employee's document")
    ResponseEntity<byte[]> file(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID employeeId,
            @PathVariable UUID id) {
        return download(service.file(TenantClaim.of(jwt), employeeId, id));
    }

    @DeleteMapping("/employees/{employeeId}/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesPeople
    @Operation(summary = "Remove a document added by mistake")
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID employeeId, @PathVariable UUID id) {
        service.remove(TenantClaim.of(jwt), employeeId, id);
    }

    private static ResponseEntity<byte[]> download(DocumentFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(f.content());
    }
}
