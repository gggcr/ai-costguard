package ai.costguard.core.imports.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum ImportProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Imports need the owner, finance or it_admin role"),
    IMPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Import not found"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "Review task not found"),
    EMPTY_FILE(HttpStatus.BAD_REQUEST, "The file is empty"),
    UNSUPPORTED_FILE(HttpStatus.BAD_REQUEST, "Supported: xlsx, xls, csv, pdf, scans (jpg, png, heic, tiff, webp, bmp) and bank statements: 1C, MT940, CAMT.053 (xml), OFX"),
    WRONG_STAGE(HttpStatus.CONFLICT, "The import is not at this stage"),
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "The mapping names a field that does not exist");

    private final HttpStatus status;
    private final String detail;

    ImportProblem(HttpStatus status, String detail) {
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
