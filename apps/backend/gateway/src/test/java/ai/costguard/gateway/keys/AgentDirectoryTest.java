package ai.costguard.gateway.keys;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.AgentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentDirectoryTest {

    private static AgentState agent(UUID id, String... secrets) {
        return AgentState.newBuilder().setTenantId(UUID.randomUUID()).setAgentId(id)
                .setVirtualKeyHashes(java.util.Arrays.stream(secrets).map(AgentDirectory::hash).toList())
                .setSuspended(false).setUpdatedAt(Instant.now()).build();
    }

    @Test
    void aNewerStateDropsTheKeysItNoLongerHas() {
        AgentDirectory directory = new AgentDirectory();
        UUID id = UUID.randomUUID();
        directory.apply(agent(id, "cg_live_old", "cg_live_kept"));
        directory.apply(agent(id, "cg_live_kept"));
        assertThat(directory.byKey("cg_live_old")).isEmpty();
        assertThat(directory.byKey("cg_live_kept")).isPresent();
    }

    @Test
    void anErasedAgentStopsAtOnceAndOthersKeepWorking() {
        AgentDirectory directory = new AgentDirectory();
        UUID erased = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        directory.apply(agent(erased, "cg_live_a", "cg_live_b"));
        directory.apply(agent(other, "cg_live_c"));

        directory.remove(erased);
        directory.remove(UUID.randomUUID());

        assertThat(List.of(directory.byKey("cg_live_a"), directory.byKey("cg_live_b"))).allMatch(o -> o.isEmpty());
        assertThat(directory.byKey("cg_live_c")).map(AgentState::getAgentId).contains(other);
    }
}
