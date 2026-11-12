package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The key a person signs documents with (ADR 0068): any signed-in person, employee or not. */
@RestController
@RequestMapping("/api/v1/me/signing-key")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class SigningKeyController {

    private final SigningKeys keys;

    SigningKeyController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping
    @Operation(summary = "Whether I have a signing key, and which: an authenticator app (totp) or a PIN")
    SigningKeyStatus status(@AuthenticationPrincipal Jwt jwt) {
        return keys.status(TenantClaim.of(jwt), jwt);
    }

    @PostMapping("/totp")
    @Operation(summary = "Start the authenticator setup: a secret and its otpauth link for the QR code",
            description = "Replacing a key that is set needs a code of the current one in current.")
    TotpSetup startTotp(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) TotpStart request) {
        return keys.startTotp(TenantClaim.of(jwt), jwt, request == null ? null : request.current());
    }

    @PutMapping("/totp")
    @Operation(summary = "Confirm the authenticator with a code it shows: it becomes my signing key")
    SigningKeyStatus confirmTotp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpConfirm request) {
        return keys.confirmTotp(TenantClaim.of(jwt), jwt, request.code());
    }

    @PutMapping("/pin")
    @Operation(summary = "Set a six-digit PIN as my signing key",
            description = "Replacing a key that is set needs a code of the current one in current.")
    SigningKeyStatus setPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PinSetup request) {
        return keys.setPin(TenantClaim.of(jwt), jwt, request.pin(), request.current());
    }
}
