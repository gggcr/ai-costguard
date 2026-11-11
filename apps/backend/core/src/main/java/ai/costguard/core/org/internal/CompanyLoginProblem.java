package ai.costguard.core.org.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum CompanyLoginProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Only an owner connects the company's Microsoft 365"),
    LOGIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak cannot be set up right now: try again later"),
    SECRET_REQUIRED(HttpStatus.BAD_REQUEST, "The client secret of the app registration is needed the first time"),
    DOMAIN_TAKEN(HttpStatus.CONFLICT, "Another company logs in with one of these e-mail domains"),
    DOMAIN_NOT_OURS(HttpStatus.BAD_REQUEST, "No user of the company has an e-mail in one of these domains"),
    NO_HINT(HttpStatus.NOT_FOUND, "No company logs in with Microsoft for this e-mail domain"),
    ENCRYPTION_KEY_MISSING(HttpStatus.SERVICE_UNAVAILABLE,
            "The server has no encryption key (CONNECTIONS_ENCRYPTION_KEY) to keep the client secret"),
    MAILBOXES_OFF(HttpStatus.CONFLICT, "The company does not make Microsoft 365 mailboxes: connect it first"),
    EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "Type the person's work e-mail or make a mailbox"),
    MAILBOX_FAILED(HttpStatus.BAD_GATEWAY,
            "Microsoft 365 did not make the mailbox: check the app's permission User.ReadWrite.All and licenses");

    private final HttpStatus status;
    private final String detail;

    CompanyLoginProblem(HttpStatus status, String detail) {
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
