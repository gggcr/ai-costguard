package ai.costguard.core.agents.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum AgentProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing agents needs the owner, it_admin or agent_owner role"),
    NOT_AGENT_OWNER(HttpStatus.FORBIDDEN, "An agent owner can change only their own agents"),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Agent not found"),
    KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "Key not found"),
    MANDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "Mandate not found"),
    MANDATE_NAME_TAKEN(HttpStatus.CONFLICT, "A mandate with this name exists"),
    MANDATE_IN_USE(HttpStatus.CONFLICT, "Agents still use this mandate"),
    THRESHOLD_ABOVE_LIMIT(HttpStatus.BAD_REQUEST, "The approval threshold cannot be above the operation limit"),
    NOTARY_HASH_MISMATCH(HttpStatus.CONFLICT, "The notary registered a hash the core did not compute");

    private final HttpStatus status;
    private final String detail;

    AgentProblem(HttpStatus status, String detail) {
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
