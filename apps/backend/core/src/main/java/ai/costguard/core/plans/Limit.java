package ai.costguard.core.plans;

/** What a plan limits in the core. Gateway operations per month are limited by the gateway itself. */
public enum Limit {
    AGENTS("agent", "agents"),
    EMPLOYEES("employee", "employees"),
    CONNECTED_SYSTEMS("connected system", "connected systems");

    private final String one;
    private final String many;

    Limit(String one, String many) {
        this.one = one;
        this.many = many;
    }

    /** How {@code n} of them are named in an error message. */
    public String noun(long n) {
        return n == 1 ? one : many;
    }
}
