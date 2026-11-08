package ai.costguard.core.connections.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum ConnectionProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing connections needs the owner or it_admin role"),
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Connection not found"),
    UNKNOWN_SYSTEM_TYPE(HttpStatus.BAD_REQUEST, "The connector agent cannot pull this type of system"),
    SETTING_MISSING(HttpStatus.BAD_REQUEST, "A required setting of this system is missing"),
    SETTING_UNKNOWN(HttpStatus.BAD_REQUEST,
            "Unknown setting; secrets are not kept in the core, they go to the agent's environment"),
    ALREADY_CONNECTED(HttpStatus.CONFLICT, "This system is already connected"),
    NOT_CONNECTED(HttpStatus.CONFLICT, "The system is disconnected; connect it again to sync");

    private final HttpStatus status;
    private final String detail;

    ConnectionProblem(HttpStatus status, String detail) {
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
