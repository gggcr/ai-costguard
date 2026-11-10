package ai.costguard.core.agents;

import java.util.Set;

/** The vendors agents reach through the gateway. Call inside a transaction already bound to the tenant. */
public interface GatewayVendors {

    /** The vendors of every mandate ever published, as people wrote them. */
    Set<String> vendors();
}
