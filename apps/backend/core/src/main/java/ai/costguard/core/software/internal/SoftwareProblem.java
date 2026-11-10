package ai.costguard.core.software.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum SoftwareProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing software needs the owner, finance or it_admin role"),
    VENDOR_NOT_FOUND(HttpStatus.NOT_FOUND, "Vendor not found"),
    CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND, "Contract not found"),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Subscription not found"),
    LICENSE_NOT_FOUND(HttpStatus.NOT_FOUND, "License not found"),
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "Plan not found"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    PLAN_NAME_TAKEN(HttpStatus.CONFLICT, "This product already has a plan with this name"),
    PLAN_WITHOUT_PRICE(HttpStatus.BAD_REQUEST, "A plan needs the price of a month, of a year or both"),
    VENDOR_NAME_TAKEN(HttpStatus.CONFLICT, "A vendor with this name exists"),
    CONTRACT_NUMBER_TAKEN(HttpStatus.CONFLICT, "This vendor already has a contract with this number"),
    CONTRACT_OF_ANOTHER_VENDOR(HttpStatus.BAD_REQUEST, "The contract belongs to another vendor"),
    DATES_REVERSED(HttpStatus.BAD_REQUEST, "The end date is before the start date"),
    SEATS_EXHAUSTED(HttpStatus.CONFLICT, "Every seat of the subscription is taken"),
    SEATS_IN_USE(HttpStatus.CONFLICT, "More licenses are assigned than the new number of seats");

    private final HttpStatus status;
    private final String detail;

    SoftwareProblem(HttpStatus status, String detail) {
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
