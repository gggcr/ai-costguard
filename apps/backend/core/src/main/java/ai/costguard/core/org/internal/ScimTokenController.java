package ai.costguard.core.org.internal;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scim/tokens")
@Tag(name = "SCIM", description = "Tokens of the SCIM server an identity provider provisions employees through")
class ScimTokenController {

    private final ScimTokens tokens;

    ScimTokenController(ScimTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    @ManagesOrg
    @Operation(summary = "List SCIM tokens", description = "The tokens themselves are never shown again.")
    List<ScimTokens.View> list(@AuthenticationPrincipal Jwt jwt) {
        return tokens.list(TenantClaim.of(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesOrg
    @Operation(summary = "Issue a SCIM token",
            description = "The answer carries the token once: put it into the provisioning settings of the identity "
                    + "provider with the base URL /scim/v2. dismissOnDeactivate: whether active = false dismisses the "
                    + "employee (Okta and Entra ID switch an account off when a person leaves).")
    ScimTokens.View issue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScimTokens.Request request) {
        return tokens.issue(TenantClaim.of(jwt), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesOrg
    @Operation(summary = "Revoke a SCIM token")
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!tokens.revoke(TenantClaim.of(jwt), id)) {
            throw OrgProblem.SCIM_TOKEN_NOT_FOUND.failure();
        }
    }
}
