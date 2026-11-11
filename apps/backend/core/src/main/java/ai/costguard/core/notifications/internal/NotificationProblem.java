package ai.costguard.core.notifications.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum NotificationProblem implements ProblemCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Your e-mail is not a CostGuard user of this company"),
    TELEGRAM_UNAVAILABLE(HttpStatus.CONFLICT, "Telegram is not configured on this server"),
    NOT_AN_EMPLOYEE(HttpStatus.NOT_FOUND, "You are not an employee of this company in CostGuard");

    private final HttpStatus status;
    private final String detail;

    NotificationProblem(HttpStatus status, String detail) {
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
