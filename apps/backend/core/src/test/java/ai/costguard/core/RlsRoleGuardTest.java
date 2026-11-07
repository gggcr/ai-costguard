package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.costguard.platform.tenant.RlsRoleGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RlsRoleGuardTest extends PostgresTestSupport {

    @Test
    void refusesARoleThatBypassesRowLevelSecurity() {
        JdbcClient superuser = JdbcClient.create(adminDataSource());

        assertThatThrownBy(() -> new RlsRoleGuard(superuser).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bypasses row-level security");
    }

    @Test
    void acceptsAnOrdinaryRole() {
        JdbcClient ordinary = JdbcClient.create(appDataSource());

        assertThatCode(() -> new RlsRoleGuard(ordinary).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
