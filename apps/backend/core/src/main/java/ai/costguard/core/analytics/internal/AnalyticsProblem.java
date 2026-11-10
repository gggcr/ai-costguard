package ai.costguard.core.analytics.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum AnalyticsProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Publishing a forecast needs the owner, finance or forecast role"),
    FORECAST_NOT_FOUND(HttpStatus.NOT_FOUND, "No forecast has been published yet"),
    SCENARIO_SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "A change names a subscription the company does not have"),
    CHANGE_NEEDS_SUBSCRIPTION(HttpStatus.BAD_REQUEST, "Only freeSeats and yearly apply to every subscription"),
    CHANGE_NEEDS_VALUE(HttpStatus.BAD_REQUEST, "seats needs seats, price needs pricePerSeat"),
    FORECAST_DOES_NOT_ADD_UP(HttpStatus.BAD_REQUEST,
            "The departments do not add up to the company, or a series has a different number of months");

    private final HttpStatus status;
    private final String detail;

    AnalyticsProblem(HttpStatus status, String detail) {
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
