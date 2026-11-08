package ai.costguard.gateway.providers;

import ai.costguard.gateway.approvals.TaskFitModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The model of the AI assessor, when {@code costguard.gateway.assessor.provider} and {@code .model} are set. The
 * provider must be one of {@code costguard.gateway.providers}; a name that is not there stops the gateway, rather
 * than silently sending everything to people.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${costguard.gateway.assessor.provider:}'.isBlank()"
        + " && !'${costguard.gateway.assessor.model:}'.isBlank()")
class AssessorModelConfig {

    @Bean
    TaskFitModel taskFitModel(ProviderProperties properties, ProviderCredentials credentials,
            @Value("${costguard.gateway.assessor.provider}") String provider,
            @Value("${costguard.gateway.assessor.model}") String model) {
        ProviderProperties.Provider settings = properties.providers() == null ? null
                : properties.providers().get(provider);
        if (settings == null) {
            throw new IllegalStateException("ASSESSOR_PROVIDER " + provider + " is not a provider of the gateway");
        }
        return new ProviderTaskFitModel(provider, settings, model, credentials, WebClient.create());
    }
}
