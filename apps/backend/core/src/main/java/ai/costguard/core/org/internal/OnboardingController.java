package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Добавить сотрудника с почтой и учётной записью; свой способ входа (ADR 0059). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation", description = "Company, departments, employees, users and dismissals")
class OnboardingController {

    private final OnboardingService onboarding;
    private final IdentityProvider accounts;
    private final String issuer;

    OnboardingController(OnboardingService onboarding, IdentityProvider accounts,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.onboarding = onboarding;
        this.accounts = accounts;
        this.issuer = issuer.replaceAll("/+$", "");
    }

    @PostMapping("/employees/onboarding")
    @ManagesOrg
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an employee with a Microsoft 365 mailbox (if connected) and a CostGuard account",
            description = "The password is in this answer only: give it to the person.")
    Onboarded add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewEmployee request) {
        return onboarding.add(TenantClaim.of(jwt), request);
    }

    @GetMapping("/me/sign-in")
    @Operation(summary = "My way of logging in: a code of an authenticator app instead of the password")
    MySignIn signIn(@AuthenticationPrincipal Jwt jwt) {
        IdentityProvider.SignIn s;
        try {
            s = accounts.signIn(jwt.getSubject());
        } catch (IdentityProvider.Unavailable e) {
            // 503, not an unhandled error: that went to /error, which an employee may not read, and said 403
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
        return new MySignIn(s.otp(), s.otpFirst(), issuer + "/account/account-security/signing-in");
    }

    @PutMapping("/me/sign-in/otp-first")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ask the code of the authenticator app first, the password stays another way")
    void otpFirst(@AuthenticationPrincipal Jwt jwt) {
        try {
            accounts.preferOtp(jwt.getSubject());
        } catch (IdentityProvider.Unavailable e) {
            throw CompanyLoginProblem.LOGIN_UNAVAILABLE.failure();
        }
    }
}
