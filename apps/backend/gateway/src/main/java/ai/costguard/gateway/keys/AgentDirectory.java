package ai.costguard.gateway.keys;

import ai.costguard.events.AgentState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Agents as {@code agents.state.v1} describes them, found by the hash of a virtual key. */
@Component
public class AgentDirectory {

    private final Map<UUID, AgentState> agents = new ConcurrentHashMap<>();
    private final Map<String, UUID> keys = new ConcurrentHashMap<>();

    /** A newer state replaces the old one in full, so a revoked key disappears with it. */
    public void apply(AgentState state) {
        AgentState previous = agents.put(state.getAgentId(), state);
        if (previous != null) {
            previous.getVirtualKeyHashes().forEach(h -> keys.remove(h, state.getAgentId()));
        }
        Set.copyOf(state.getVirtualKeyHashes()).forEach(h -> keys.put(h, state.getAgentId()));
    }

    public Optional<AgentState> byKey(String secret) {
        return Optional.ofNullable(keys.get(hash(secret))).map(agents::get);
    }

    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
