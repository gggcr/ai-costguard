package ai.costguard.gateway.providers;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider endpoints and credentials. Keys come from Vault in production through the same properties. Agents never
 * see them: the gateway takes the agent's virtual key off the request and puts the provider's key on.
 */
@ConfigurationProperties("costguard.gateway")
public record ProviderProperties(Map<String, Provider> providers, Long defaultMaxTokens) {

    public record Provider(URI url, String vendor, String apiKey, String folderId, URI oauthUrl, String scope) {
    }

    public long maxTokens() {
        return defaultMaxTokens == null ? 1024 : defaultMaxTokens;
    }
}
