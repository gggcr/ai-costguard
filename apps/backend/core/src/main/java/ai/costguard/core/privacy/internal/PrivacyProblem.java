package ai.costguard.core.privacy.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum PrivacyProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Erasing data needs the owner role; reading erasures, owner or auditor"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Employee not found"),
    EMPLOYEE_ACTIVE(HttpStatus.CONFLICT, "The employee still works here; dismiss them before erasing their data"),
    LAST_OWNER(HttpStatus.CONFLICT, "The employee is the only owner of the company; make someone else owner first"),
    ERASURE_NOT_FOUND(HttpStatus.NOT_FOUND, "Erasure not found"),
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "The company has no saved settings yet"),
    CONFIRM_NAME_MISMATCH(HttpStatus.BAD_REQUEST, "confirmName must be the company name exactly as it is saved"),
    COMPANY_ERASURE_NOT_FOUND(HttpStatus.NOT_FOUND, "No erasure of the company is scheduled"),
    IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "Keycloak did not answer; nothing was erased, try again");

    private final HttpStatus status;
    private final String detail;

    PrivacyProblem(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String detail() {
        return detail;
    }
}
