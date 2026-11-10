package ai.costguard.core.analytics.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ForecastRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    ForecastRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, Instant at, ForecastDocument document) {
        String body;
        try {
            body = JSON.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A forecast that cannot be written as JSON", e);
        }
        jdbc.sql("insert into forecast (id, tenant_id, published_at, body) values (?, ?, ?, cast(? as jsonb))")
                .params(id, tenantId, Timestamp.from(at), body).update();
    }

    Optional<PublishedForecast> newest() {
        return jdbc.sql("select id, published_at, body::text as body from forecast order by published_at desc limit 1")
                .query((rs, n) -> {
                    try {
                        return new PublishedForecast(rs.getObject("id", UUID.class),
                                rs.getTimestamp("published_at").toInstant(),
                                JSON.readValue(rs.getString("body"), ForecastDocument.class));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("A stored forecast that cannot be read", e);
                    }
                }).optional();
    }
}
