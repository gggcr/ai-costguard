package ai.costguard.core.cabinet.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum CabinetProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "This needs the owner, finance or it_admin role"),
    NOT_AN_EMPLOYEE(HttpStatus.NOT_FOUND, "You are not an employee of this company in CostGuard"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Employee not found"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Document not found"),
    FILE_EMPTY(HttpStatus.BAD_REQUEST, "The file is empty"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "A document may be at most 20 MB");

    private final HttpStatus status;
    private final String detail;

    CabinetProblem(HttpStatus status, String detail) {
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
