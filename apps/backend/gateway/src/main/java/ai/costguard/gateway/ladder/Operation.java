package ai.costguard.gateway.ladder;

import ai.costguard.events.AgentState;
import ai.costguard.events.OperationKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One operation on its way through the ladder. Steps fill in what they learn: the identity step the agent, the
 * budget step whether money is reserved. Only one request thread touches an instance.
 */
public final class Operation {

    private final UUID operationId;
    private final String secret;
    private final String taskId;
    private final OperationKind kind;
    private final String vendor;
    private final String model;
    private final Instant at;

    private BigDecimal estimatedCost;
    private long inputTokens;
    private Long maxOutputTokens;
    private AgentState agent;
    private boolean reserved;
    private boolean needsApproval;
    private String approvalReason;
    private String taskGoal;
    private String countedIn;
    private java.util.function.Supplier<java.util.Optional<BigDecimal>> estimate;

    public Operation(UUID operationId, String secret, String taskId, OperationKind kind, String vendor, String model,
            BigDecimal estimatedCost, Instant at) {
        this.operationId = operationId;
        this.secret = secret;
        this.taskId = taskId;
        this.kind = kind;
        this.vendor = vendor;
        this.model = model;
        this.estimatedCost = estimatedCost;
        this.at = at;
    }

    public UUID operationId() { return operationId; }
    public String secret() { return secret; }
    public String taskId() { return taskId; }
    public OperationKind kind() { return kind; }
    public String vendor() { return vendor; }
    public String model() { return model; }
    public Instant at() { return at; }
    public BigDecimal estimatedCost() { return estimatedCost; }
    public long inputTokens() { return inputTokens; }
    public Long maxOutputTokens() { return maxOutputTokens; }
    public AgentState agent() { return agent; }
    public UUID tenantId() { return agent == null ? null : agent.getTenantId(); }
    public UUID agentId() { return agent == null ? null : agent.getAgentId(); }
    public boolean reserved() { return reserved; }
    public boolean needsApproval() { return needsApproval; }
    public String approvalReason() { return approvalReason; }

    /** The goal of the task as the agent states it ({@link TaskGoal}); null when it sent none. */
    public String taskGoal() { return taskGoal; }
    public void taskGoal(String goal) { this.taskGoal = goal; }

    /**
     * Sets the agent and, for a proxied call, prices the call in that agent's catalog. A call with no price gets
     * no estimate, and the mandate step then refuses it.
     */
    public void agent(AgentState agent) {
        this.agent = agent;
        if (estimate != null) {
            this.estimatedCost = estimate.get().orElse(null);
        }
    }

    public void onIdentified(java.util.function.Supplier<java.util.Optional<BigDecimal>> estimate) {
        this.estimate = estimate;
    }
    public void reserved(boolean reserved) { this.reserved = reserved; }
    public void tokens(long input, Long maxOutput) { this.inputTokens = input; this.maxOutputTokens = maxOutput; }
    public void estimatedCost(BigDecimal cost) { this.estimatedCost = cost; }

    /** The month (yyyy-MM, UTC) whose plan quota this operation took a place in; null when it took none. */
    public String countedIn() { return countedIn; }
    public void countedIn(String month) { this.countedIn = month; }

    /** The first reason wins; it is what people see on the approval. */
    public void needApproval(String reason) {
        if (!needsApproval) {
            needsApproval = true;
            approvalReason = reason;
        }
    }
}
