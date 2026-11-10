package ai.costguard.core.org.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationConfig implements WebMvcConfigurer {

    private final FirstLogin firstLogin;

    RegistrationConfig(FirstLogin firstLogin) {
        this.firstLogin = firstLogin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(firstLogin).addPathPatterns("/api/**");
    }
}
