package ai.costguard.platform;

import ai.costguard.platform.tenant.TenantSession;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Entry point of the shared starter. Register beans from the subpackages here as they appear. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
public class PlatformAutoConfiguration {

    /** Only for services with a database: the gateway has none. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.simple.JdbcClient")
    @ConditionalOnBean(type = "org.springframework.jdbc.core.simple.JdbcClient")
    static class Rls {

        @Bean
        TenantSession tenantSession(JdbcClient jdbc) {
            return new TenantSession(jdbc);
        }
    }
}
