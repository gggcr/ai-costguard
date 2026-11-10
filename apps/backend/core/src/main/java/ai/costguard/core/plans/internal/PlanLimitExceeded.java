package ai.costguard.core.plans.internal;

import ai.costguard.core.plans.Limit;
import ai.costguard.platform.errors.ProblemCode;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 402 {@code plan_limit_exceeded}: the detail says what the plan allows and what the company has, and the body
 * carries the same as numbers ({@code limit}, {@code allowed}, {@code used}, {@code plan}) for the client.
 */
record PlanLimitExceeded(Limit limit, String plan, String planTitle, long allowed, long used) implements ProblemCode {

    @Override
    public String name() {
        return "PLAN_LIMIT_EXCEEDED";
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.PAYMENT_REQUIRED;
    }

    @Override
    public String detail() {
        return "The " + planTitle + " plan allows " + allowed + " " + limit.noun(allowed) + " and the company has "
                + used + ". Move to a bigger plan or remove some to add more.";
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("limit", limit.name().toLowerCase(Locale.ROOT), "plan", plan, "allowed", allowed, "used", used);
    }
}
