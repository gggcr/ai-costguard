package ai.costguard.core.cabinet.internal;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Отпуска (ADR 0053). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class VacationController {

    private final VacationService service;

    VacationController(VacationService service) {
        this.service = service;
    }

    @GetMapping("/me/vacations")
    @Operation(summary = "My vacation days of a year (this one by default) and all my requests")
    VacationBalance mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Integer year) {
        return service.mine(TenantClaim.of(jwt), jwt, year);
    }

    @PostMapping("/me/vacations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask for a vacation; the head of my department is told")
    Vacation ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewVacation request) {
        return service.ask(TenantClaim.of(jwt), jwt, request);
    }

    @DeleteMapping("/me/vacations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel my request, or my approved vacation that has not started")
    void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancel(TenantClaim.of(jwt), jwt, id);
    }

    @GetMapping("/me/team/vacations")
    @Operation(summary = "Vacation requests of the people of the departments I head")
    List<Vacation> team(@AuthenticationPrincipal Jwt jwt) {
        return service.team(TenantClaim.of(jwt), jwt);
    }

    @PutMapping("/me/team/vacations/{id}/decision")
    @Operation(summary = "Decide on a request of my department, as its head")
    Vacation decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody VacationDecision decision) {
        return service.decideAsHead(TenantClaim.of(jwt), jwt, id, decision);
    }

    @GetMapping("/vacations")
    @ManagesPeople
    @Operation(summary = "All vacation requests of the company")
    List<Vacation> all(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) VacationStatus status) {
        return service.all(TenantClaim.of(jwt), status);
    }

    @PutMapping("/vacations/{id}/decision")
    @ManagesPeople
    @Operation(summary = "Decide on a request as the employer (a department without a head)")
    Vacation decideAsEmployer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody VacationDecision decision) {
        return service.decideAsEmployer(TenantClaim.of(jwt), id, decision, Whoami.who(jwt));
    }
}
