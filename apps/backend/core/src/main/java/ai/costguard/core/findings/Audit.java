package ai.costguard.core.findings;

import java.util.UUID;

/** Runs every audit rule for one tenant now; the engine also does it on its own schedule. */
public interface Audit {

    /** @return how many findings the rules reported */
    int run(UUID tenantId);
}
