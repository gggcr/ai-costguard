package ai.costguard.core.catalog.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum CatalogProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing prices needs the owner, finance or it_admin role"),
    PRICE_EXISTS(HttpStatus.CONFLICT, "This SKU already has a price from this date"),
    RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "No exchange rate on or before this date");

    private final HttpStatus status;
    private final String detail;

    CatalogProblem(HttpStatus status, String detail) {
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
