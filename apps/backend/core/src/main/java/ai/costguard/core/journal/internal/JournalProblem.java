package ai.costguard.core.journal.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum JournalProblem implements ProblemCode {
    RECEIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "No receipt for this operation"),
    ROOT_MISMATCH(HttpStatus.CONFLICT, "The Merkle root of the batch does not match its receipts"),
    OPERATIONS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The operation journal (ClickHouse) does not answer, try later");

    private final HttpStatus status;
    private final String detail;

    JournalProblem(HttpStatus status, String detail) {
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
