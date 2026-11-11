package ai.costguard.core.shared.security;

import ai.costguard.platform.errors.ProblemHandler;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API call needs a Keycloak token; health checks, the OpenAPI spec, self-registration and payment provider
 * notices stay open.
 */
@Configuration
@EnableMethodSecurity
@OpenAPIDefinition(
        info = @Info(title = "CostGuard Core API", version = "v1"),
        servers = @Server(url = "/"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class SecurityConfig {

    /** Roles that may read company data: people (ADR 0001) and service accounts (connector, forecast, importer). */
    static final String[] STAFF = {"owner", "finance", "it_admin", "agent_owner", "auditor", "connector", "forecast",
            "importer"};

    @Bean
    SecurityFilterChain api(HttpSecurity http, RealmRoles realmRoles, ProblemHandler problems) throws Exception {
        // the same problem+json with code role_required as the controllers' own role checks
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        JwtAuthenticationConverter tokens = new JwtAuthenticationConverter();
        tokens.setJwtGrantedAuthoritiesConverter(realmRoles);
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**").permitAll()
                        // Self-registration and payment provider notices come without a token (ADR 0036, 0038)
                        .requestMatchers(HttpMethod.POST, "/api/v1/registrations",
                                "/api/v1/billing/notifications/*").permitAll()
                        // the login page asks where a work e-mail logs in before anyone is signed in (ADR 0058)
                        .requestMatchers(HttpMethod.GET, "/api/v1/login/hint").permitAll()
                        // The employee's cabinet and their own delivery settings: any signed-in person (ADR 0051)
                        .requestMatchers("/api/v1/me", "/api/v1/me/**", "/api/v1/notifications/settings",
                                "/api/v1/notifications/telegram-link", "/api/v1/notifications/telegram")
                        .authenticated()
                        // the specification as YAML, as before: any signed-in client
                        .requestMatchers("/v3/api-docs.yaml").authenticated()
                        // Everything else is the company's data: a staff or service role, never employee alone.
                        // Controllers check finer rights on top.
                        .anyRequest().hasAnyRole(STAFF))
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, denied) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    var body = problems.body(SecurityProblem.ROLE_REQUIRED);
                    var out = new java.util.LinkedHashMap<String, Object>();
                    out.put("type", body.getType() == null ? "about:blank" : body.getType().toString());
                    out.put("title", body.getTitle() == null ? "Forbidden" : body.getTitle());
                    out.put("status", body.getStatus());
                    out.put("detail", body.getDetail());
                    out.put("instance", request.getRequestURI());
                    if (body.getProperties() != null) {
                        out.putAll(body.getProperties());
                    }
                    json.writeValue(response.getOutputStream(), out);
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(tokens)))
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
