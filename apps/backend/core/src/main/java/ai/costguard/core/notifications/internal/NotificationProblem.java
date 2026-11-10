package ai.costguard.core.notifications.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum NotificationProblem implements ProblemCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Your e-mail is not a CostGuard user of this company"),
    TELEGRAM_UNAVAILABLE(HttpStatus.CONFLICT, "Telegram is not configured on this server");

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
