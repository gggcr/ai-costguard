package ai.costguard.core.org.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum RegistrationProblem implements ProblemCode {
    REGISTRATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Registration is not available right now"),
    TOO_MANY_REGISTRATIONS(HttpStatus.TOO_MANY_REQUESTS, "Too many registration attempts: try again in an hour"),
    COUNTRY_UNKNOWN(HttpStatus.BAD_REQUEST, "country must be an ISO 3166-1 alpha-2 code, such as RU"),
    LANGUAGE_UNKNOWN(HttpStatus.BAD_REQUEST, "language must be ru or en"),
    TIME_ZONE_UNKNOWN(HttpStatus.BAD_REQUEST, "timeZone must be an IANA zone, such as America/Chicago"),
    CURRENCY_UNKNOWN(HttpStatus.BAD_REQUEST, "currency must be an ISO 4217 code, such as RUB"),
    SETUP_EMAIL_FAILED(HttpStatus.SERVICE_UNAVAILABLE,
            "The e-mail could not be sent: send the same request again in a few minutes"),
    OWNER_REQUIRED(HttpStatus.FORBIDDEN, "Only an owner can invite people"),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Invitation not found"),
    INVITATION_ACCEPTED(HttpStatus.CONFLICT,
            "The person has accepted the invitation: close their access in the users list instead"),
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "This e-mail already has a CostGuard account and cannot be invited");

    private final HttpStatus status;
    private final String detail;

    RegistrationProblem(HttpStatus status, String detail) {
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
