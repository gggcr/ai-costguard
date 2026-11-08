package ai.costguard.core.shared.security;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Every API call needs a Keycloak token; health checks and the OpenAPI spec stay open. */
@Configuration
@EnableMethodSecurity
@OpenAPIDefinition(
        info = @Info(title = "CostGuard Core API", version = "v1"),
        servers = @Server(url = "/"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, RealmRoles realmRoles) throws Exception {
        JwtAuthenticationConverter tokens = new JwtAuthenticationConverter();
        tokens.setJwtGrantedAuthoritiesConverter(realmRoles);
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(tokens)))
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
