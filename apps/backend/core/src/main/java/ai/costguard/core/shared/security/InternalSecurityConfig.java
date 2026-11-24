package ai.costguard.core.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /internal/**} is for the gateway of an install without Kafka (ADR 0080), and only there: with
 * {@code EVENTS_TRANSPORT=direct} it takes the shared {@code INTERNAL_TOKEN} in the {@code X-Internal-Token} header,
 * never a user's token. Without direct transport this chain does not exist, and the paths fall to {@link SecurityConfig},
 * where nothing answers them. The core refuses to start with a token shorter than 32 characters.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "costguard.events.transport", havingValue = "direct")
class InternalSecurityConfig {

    static final String HEADER = "X-Internal-Token";
    static final int MIN_LENGTH = 32;

    @Bean
    @Order(0)
    SecurityFilterChain internal(HttpSecurity http, @Value("${costguard.events.internal-token:}") String token)
            throws Exception {
        if (token.length() < MIN_LENGTH) {
            throw new IllegalStateException("EVENTS_TRANSPORT=direct needs INTERNAL_TOKEN of at least " + MIN_LENGTH
                    + " characters, the same for the core and the gateway (for example: openssl rand -hex 32).");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return http
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("internal"))
                .addFilterBefore(new TokenFilter(expected), AbstractPreAuthenticatedProcessingFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    static final class TokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        TokenFilter(byte[] expected) {
            this.expected = expected;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String given = request.getHeader(HEADER);
            // compared in constant time: the time of a refusal tells nothing about how much of the token was right
            if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "gateway", null, List.of(new SimpleGrantedAuthority("ROLE_internal"))));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
