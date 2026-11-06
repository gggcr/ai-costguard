package ai.costguard.core.shared.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Keycloak puts realm roles in realm_access.roles, a claim Spring Security does not read by itself. */
@Component
public class RealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> access = jwt.getClaimAsMap("realm_access");
        if (access == null || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}
