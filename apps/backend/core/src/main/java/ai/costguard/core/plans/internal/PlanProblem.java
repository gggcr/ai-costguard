package ai.costguard.core.plans.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum PlanProblem implements ProblemCode {
    PLAN_READ_ONLY(HttpStatus.PAYMENT_REQUIRED, "The trial or the paid period has ended: the company is read-only "
            + "until a plan is paid. Choose a plan on the plan page."),
    PLAN_UNKNOWN(HttpStatus.BAD_REQUEST, "No such plan to pay for: choose start or business"),
    PLAN_NOT_ASSIGNED(HttpStatus.NOT_FOUND, "The company has no plan yet and works without limits"),
    PAYMENT_PROVIDER_MISSING(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not configured yet"),
    PAYMENT_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY, "The payment provider did not answer: try again in a few minutes"),
    PAYMENT_PROVIDER_UNKNOWN(HttpStatus.NOT_FOUND, "No such payment provider"),
    PAYMENT_NOTICE_INVALID(HttpStatus.BAD_REQUEST, "The payment notice could not be verified"),
    PAYMENT_UNKNOWN(HttpStatus.NOT_FOUND, "No such payment"),
    PLAN_CURRENCY_UNKNOWN(HttpStatus.BAD_REQUEST, "The plan is not sold in this currency"),
    SUBSCRIPTION_EXISTS(HttpStatus.CONFLICT, "The company already has a subscription: change or cancel it in the "
            + "billing portal"),
    SUBSCRIPTION_MISSING(HttpStatus.CONFLICT, "The company has no subscription at the payment provider yet"),
    // Stripe delivers events in any order: an event about a subscription the core has not heard of yet is answered
    // with an error, and Stripe delivers it again later, after the checkout that started it.
    SUBSCRIPTION_UNKNOWN(HttpStatus.CONFLICT, "No such subscription yet"),
    OWNER_REQUIRED(HttpStatus.FORBIDDEN, "Only an owner can pay for a plan");

    private final HttpStatus status;
    private final String detail;

    PlanProblem(HttpStatus status, String detail) {
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
