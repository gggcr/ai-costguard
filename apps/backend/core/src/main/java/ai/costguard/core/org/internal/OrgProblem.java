package ai.costguard.core.org.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum OrgProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing the organisation needs the owner or it_admin role"),
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "Company not found"),
    DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Department not found"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Employee not found"),
    SCIM_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "SCIM token not found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    DEPARTMENT_NAME_TAKEN(HttpStatus.CONFLICT, "A department with this name exists"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "This email is already registered"),
    LAST_OWNER(HttpStatus.CONFLICT, "A company must keep at least one owner"),
    EMPLOYEE_ERASED(HttpStatus.CONFLICT, "The employee's data was erased; add the person again if they return");

    private final HttpStatus status;
    private final String detail;

    OrgProblem(HttpStatus status, String detail) {
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
