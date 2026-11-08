package ai.costguard.platform;

import ai.costguard.platform.errors.ProblemHandler;
import ai.costguard.platform.tenant.RlsRoleGuard;
import ai.costguard.platform.tenant.TenantSession;
import ai.costguard.platform.tracing.TraceIdSource;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Entry point of the shared starter. Register beans from the subpackages here as they appear. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
public class PlatformAutoConfiguration {

    @Bean
    ProblemHandler problemHandler(TraceIdSource traces) {
        return new ProblemHandler(traces);
    }

    /** Tracing may be off, as it is in tests: errors then simply carry no trace_id. */
    @Bean
    TraceIdSource traceIdSource(ObjectProvider<Tracer> tracer) {
        return () -> Optional.ofNullable(tracer.getIfAvailable())
                .map(Tracer::currentSpan)
                .map(Span::context)
                .map(context -> context.traceId());
    }

    /** Only for services with a database: the gateway has none. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.simple.JdbcClient")
    @ConditionalOnBean(type = "org.springframework.jdbc.core.simple.JdbcClient")
    static class Rls {

        @Bean
        TenantSession tenantSession(JdbcClient jdbc) {
            return new TenantSession(jdbc);
        }

        @Bean
        RlsRoleGuard rlsRoleGuard(JdbcClient jdbc) {
            return new RlsRoleGuard(jdbc);
        }
    }
}
