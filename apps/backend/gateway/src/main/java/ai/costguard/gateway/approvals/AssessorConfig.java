package ai.costguard.gateway.approvals;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the assessor of step 6 in one place. A model is there only when {@code costguard.gateway.assessor} names a
 * provider and a model ({@code ASSESSOR_PROVIDER}, {@code ASSESSOR_MODEL}); without one every operation above the
 * threshold goes to a person ({@link HumanOnlyAssessor}).
 */
@Configuration(proxyBeanMethods = false)
class AssessorConfig {

    private static final Logger log = LoggerFactory.getLogger(AssessorConfig.class);

    @Bean
    TaskFitAssessor taskFitAssessor(ObjectProvider<TaskFitModel> model,
            @Value("${costguard.gateway.assessor.timeout:PT3S}") Duration timeout) {
        TaskFitModel m = model.getIfAvailable();
        if (m == null) {
            log.info("No AI assessor configured: operations above the threshold go to a person");
            return new HumanOnlyAssessor();
        }
        log.info("AI assessor on, timeout {}", timeout);
        return new LlmTaskFitAssessor(m, timeout);
    }
}
