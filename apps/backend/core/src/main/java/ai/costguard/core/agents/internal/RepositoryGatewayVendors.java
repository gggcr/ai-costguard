package ai.costguard.core.agents.internal;

import ai.costguard.core.agents.GatewayVendors;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A mandate's terms reach the gateway on publication, so a mandate never published routes nothing. */
@Component
class RepositoryGatewayVendors implements GatewayVendors {

    private final JdbcClient jdbc;

    RepositoryGatewayVendors(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> vendors() {
        return new TreeSet<>(jdbc.sql("select distinct unnest(vendors) from mandate where published_expression is not null")
                .query(String.class).list());
    }
}
