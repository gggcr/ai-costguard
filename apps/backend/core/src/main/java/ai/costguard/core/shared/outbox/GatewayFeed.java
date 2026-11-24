package ai.costguard.core.shared.outbox;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The topics the gateway reads, kept in PostgreSQL for installs without Kafka (ADR 0080). A write replaces the
 * key's row and gives it the next seq, as compaction keeps the newest record of a key. Writes take one advisory
 * lock until their transaction ends, so seqs become visible in their order: a reader that has seen seq n never
 * misses a smaller seq committed later. The outbox writes one row per transaction, so the lock is short.
 */
class GatewayFeed {

    /** What {@code StateListeners} of the gateway reads; the other topics have no reader without Kafka. */
    static final Set<String> TOPICS = Set.of("agents.state.v1", "policy.snapshots.v1", "catalog.prices.v1",
            "catalog.rates.v1", "budgets.limits.v1", "approvals.decisions.v1", "tenants.plans.v1");

    /** "cgfeed" in ASCII: the advisory lock of the feed's writers. */
    private static final long LOCK = 0x636766656564L;

    record Entry(long seq, String topic, String key, byte[] value) {
    }

    private final JdbcClient jdbc;

    GatewayFeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run in a transaction (the outbox listener's own): the lock is released at its end. */
    void write(String topic, String key, byte[] value) {
        if (!TOPICS.contains(topic)) {
            return;
        }
        jdbc.sql("select pg_advisory_xact_lock(?)").param(LOCK).query().singleRow();
        // max + 1, not a sequence: the backup role reads tables only, and a sequence stopped pg_dump (V56). Rows are
        // never deleted (a tombstone keeps its row), so the max only grows, and the lock makes it the writer's own.
        jdbc.sql("""
                insert into gateway_feed (topic, key, value, seq)
                values (?, ?, ?, (select coalesce(max(seq), 0) + 1 from gateway_feed))
                on conflict (topic, key) do update set value = excluded.value, seq = excluded.seq""")
                .params(topic, key, value).update();
    }

    /**
     * Made with the table, so a recreated database has another one: its seqs start again, and a gateway that sees a
     * new epoch reads the feed from 0 instead of waiting for seqs past the old ones.
     */
    UUID epoch() {
        return jdbc.sql("select id from gateway_feed_epoch").query(UUID.class).single();
    }

    List<Entry> after(long seq, int limit) {
        return jdbc.sql("select seq, topic, key, value from gateway_feed where seq > ? order by seq limit ?")
                .params(seq, limit)
                .query((rs, n) -> new Entry(rs.getLong("seq"), rs.getString("topic"), rs.getString("key"),
                        rs.getBytes("value")))
                .list();
    }
}
