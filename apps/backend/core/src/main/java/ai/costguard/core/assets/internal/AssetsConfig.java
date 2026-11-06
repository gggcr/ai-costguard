package ai.costguard.core.assets.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AssetsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
