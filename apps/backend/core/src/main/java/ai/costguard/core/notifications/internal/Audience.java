package ai.costguard.core.notifications.internal;

import java.util.Set;
import java.util.UUID;

/**
 * Who gets a notification outside the web client (e-mail, Telegram): users with one of these roles, the user linked
 * to this employee, and the user linked to the owner of this agent. The feed in the web client is the company's.
 */
record Audience(Set<String> roles, UUID employeeId, UUID agentId) {

    static Audience roles(String... roles) {
        return new Audience(Set.of(roles), null, null);
    }

    Audience andEmployee(UUID employee) {
        return new Audience(roles, employee, agentId);
    }

    /** The owner of the agent, found when the notification is stored. */
    Audience andOwnerOf(UUID agent) {
        return new Audience(roles, employeeId, agent);
    }
}
