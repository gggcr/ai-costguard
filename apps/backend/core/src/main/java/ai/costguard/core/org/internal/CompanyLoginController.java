package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.platform.errors.ProblemHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Вход через Microsoft 365 (ADR 0058). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation", description = "Company, departments, employees, users and dismissals")
class CompanyLoginController {

    private final CompanyLoginService service;
    private final ProblemHandler problems;

    CompanyLoginController(CompanyLoginService service, ProblemHandler problems) {
        this.service = service;
        this.problems = problems;
    }

    @GetMapping("/company/login")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "How the company's people log in: its Microsoft 365, if connected")
    CompanyLogin get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(TenantClaim.of(jwt));
    }

    @PutMapping("/company/login")
    @PreAuthorize("hasRole('owner')")
    @Operation(summary = "Connect or change the company's Microsoft 365 (Entra ID) login")
    CompanyLogin save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompanyLoginSettings settings) {
        return service.save(TenantClaim.of(jwt), settings);
    }

    @DeleteMapping("/company/login")
    @PreAuthorize("hasRole('owner')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect the Microsoft 365 login; passwords still work")
    void remove(@AuthenticationPrincipal Jwt jwt) {
        service.remove(TenantClaim.of(jwt));
    }

    @GetMapping("/login/hint")
    @Operation(summary = "Before signing in: where a work e-mail logs in", description = "Public: the login page asks.")
    LoginHint hint(@RequestParam String email) {
        return service.hint(email).orElseThrow(CompanyLoginProblem.NO_HINT::failure);
    }

    @ExceptionHandler
    ProblemDetail denied(AccessDeniedException denied) {
        return problems.body(CompanyLoginProblem.ROLE_REQUIRED);
    }
}
