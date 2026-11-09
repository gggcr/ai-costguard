package ai.costguard.core.journal.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Journal", description = "Signed receipts of operations and their anchoring in NEAR")
class JournalController {

    private final JournalService service;

    JournalController(JournalService service) {
        this.service = service;
    }

    @GetMapping("/operations/{operationId}/receipt")
    @Operation(summary = "Receipt of an operation with its Merkle path once anchored")
    ReceiptView receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID operationId) {
        return service.receipt(TenantClaim.of(jwt), operationId);
    }

    @GetMapping("/journal/verification")
    @Operation(summary = "Check the whole receipt chain of the company")
    ChainVerification verify(@AuthenticationPrincipal Jwt jwt) {
        return service.verify(TenantClaim.of(jwt));
    }

    @GetMapping("/journal/signing-key")
    @Operation(summary = "Public key that verifies receipt signatures")
    SigningKey key() {
        return service.key();
    }

    @GetMapping("/journal/signing-keys")
    @Operation(summary = "Every public key that verifies receipts: the current one first, then those it replaced")
    java.util.List<SigningKey> keys() {
        return service.keys();
    }
}
