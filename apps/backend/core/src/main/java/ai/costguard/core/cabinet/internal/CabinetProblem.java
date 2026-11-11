package ai.costguard.core.cabinet.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum CabinetProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "This needs the owner, finance or it_admin role"),
    NOT_AN_EMPLOYEE(HttpStatus.NOT_FOUND, "You are not an employee of this company in CostGuard"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Employee not found"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Document not found"),
    FILE_EMPTY(HttpStatus.BAD_REQUEST, "The file is empty"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "A document may be at most 20 MB"),
    VACATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Vacation request not found"),
    VACATION_DATES(HttpStatus.BAD_REQUEST, "The vacation ends before it starts"),
    VACATION_IN_PAST(HttpStatus.BAD_REQUEST, "A vacation cannot start in the past"),
    VACATION_OVERLAP(HttpStatus.CONFLICT, "These dates overlap another request of yours"),
    VACATION_DAYS_EXCEEDED(HttpStatus.CONFLICT, "Not enough annual vacation days left this year"),
    VACATION_DECIDED(HttpStatus.CONFLICT, "The request is already decided"),
    NOT_THE_HEAD(HttpStatus.FORBIDDEN, "Only the head of the employee's department decides on this request"),
    PAYROLL_COLUMNS(HttpStatus.BAD_REQUEST,
            "The sheet needs the columns: сотрудник (or e-mail), дата выплаты, сумма; вид and период are optional"),
    PAYROLL_FILE(HttpStatus.BAD_REQUEST, "The file is not a readable .xlsx or .csv");

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
