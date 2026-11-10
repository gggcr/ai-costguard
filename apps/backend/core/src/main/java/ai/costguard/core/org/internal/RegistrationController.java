package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Registration", description = "Self-registration of a company and invitations of its people")
class RegistrationController {

    private final RegistrationService registrations;
    private final InvitationService invitations;

    RegistrationController(RegistrationService registrations, InvitationService invitations) {
        this.registrations = registrations;
        this.invitations = invitations;
    }

    @PostMapping("/registrations")
    @SecurityRequirements
    @Operation(summary = "Register a company", description = "No token. Creates the company, its owner and a trial; "
            + "the owner gets an e-mail to confirm the address and set a password, and the company becomes active at "
            + "the first login. Always 202 check_email for a valid request, whether or not the e-mail is known. "
            + "429 too_many_registrations: per address and per e-mail. Unconfirmed registrations are dropped.")
    ResponseEntity<RegistrationAccepted> register(@Valid @RequestBody RegistrationRequest request,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(registrations.register(request, http.getRemoteAddr()));
    }

    @PostMapping("/invitations")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Invite a person with a role", description = "Owner only. The person gets an e-mail to "
            + "confirm the address and set a password, and appears in the users list at once.")
    ResponseEntity<Invitation> invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewInvitation request) {
        Invitation created = invitations.invite(TenantClaim.of(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/invitations/" + created.id())).body(created);
    }

    @GetMapping("/invitations")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "List invitations")
    List<Invitation> invitations(@AuthenticationPrincipal Jwt jwt) {
        return invitations.invitations(TenantClaim.of(jwt));
    }

    @PostMapping("/invitations/{id}/resend")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Send the invitation e-mail again", description = "Only while it is not accepted.")
    ResponseEntity<Void> resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        invitations.resend(TenantClaim.of(jwt), id);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/invitations/{id}")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Take back an invitation", description = "Only while it is not accepted: the account and "
            + "the user are deleted.")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        invitations.revoke(TenantClaim.of(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
