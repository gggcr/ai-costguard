package ai.costguard.core.plans.internal;

import java.util.List;

/** The plans the database and the catalog know. */
final class PlanName {

    static final String TRIAL = "trial";
    static final String START = "start";
    static final String BUSINESS = "business";
    static final List<String> ALL = List.of(TRIAL, START, BUSINESS);
    static final List<String> PAID = List.of(START, BUSINESS);

    private PlanName() {
    }
}
