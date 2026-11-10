package ai.costguard.core.org.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /scim/v2 is called by an identity provider with a SCIM token, not by a person with a Keycloak token (ADR 0043).
 * This chain goes before the one of the API and lets a request through only with a token that is not revoked; the
 * company of the token is the request's attribute {@link #AUTH_ATTRIBUTE}.
 */
@Configuration
class ScimSecurity {

    static final String AUTH_ATTRIBUTE = "costguard.scim.auth";

    @Bean
    @Order(1)
    SecurityFilterChain scim(HttpSecurity http, ScimTokens tokens) throws Exception {
        return http.securityMatcher("/scim/v2/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new TokenFilter(tokens), AnonymousAuthenticationFilter.class)
                .build();
    }

    /** Not a bean: a bean filter would run for every request of the application. */
    static final class TokenFilter extends OncePerRequestFilter {

        private final ScimTokens tokens;

        TokenFilter(ScimTokens tokens) {
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7).trim() : null;
            var auth = tokens.authenticate(token);
            if (auth.isEmpty()) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer realm=\"scim\"");
                response.setContentType(ScimController.SCIM_JSON);
                response.getOutputStream().write(ScimController.errorJson(401, null, "A valid SCIM token is required")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            request.setAttribute(AUTH_ATTRIBUTE, auth.get());
            chain.doFilter(request, response);
        }
    }
}
