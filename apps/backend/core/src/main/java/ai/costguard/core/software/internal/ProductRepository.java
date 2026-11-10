package ai.costguard.core.software.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PRODUCT and ACTIVITY_EVENT (ADR 0042). Reads carry no tenant filter: RLS scopes them. */
@Repository
class ProductRepository {

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Two spellings of one product are one row: lower case, single spaces. */
    static String key(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The product of the vendor by this name, created when new. */
    UUID productOf(UUID tenantId, UUID vendorId, String name) {
        jdbc.sql("""
                insert into product (id, tenant_id, vendor_id, name, name_key) values (?, ?, ?, ?, ?)
                on conflict (tenant_id, vendor_id, name_key) do nothing
                """).params(UUID.randomUUID(), tenantId, vendorId, name.strip(), key(name)).update();
        return jdbc.sql("select id from product where vendor_id = ? and name_key = ?").params(vendorId, key(name))
                .query(UUID.class).single();
    }

    void linkSubscription(UUID subscriptionId, UUID productId) {
        jdbc.sql("update subscription set product_id = ? where id = ?").params(productId, subscriptionId).update();
    }

    List<Product> products() {
        return jdbc.sql("""
                select p.id, p.vendor_id, p.name, p.category,
                       (select count(*) from subscription s where s.product_id = p.id) as subscriptions,
                       (select max(e.occurred_at) from activity_event e where e.product_id = p.id) as last_activity
                from product p order by p.name, p.id
                """).query((rs, n) -> new Product(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class),
                        rs.getString("name"), rs.getString("category"), rs.getInt("subscriptions"),
                        rs.getTimestamp("last_activity") == null ? null : rs.getTimestamp("last_activity").toInstant()))
                .list();
    }

    Optional<Product> product(UUID id) {
        return products().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    boolean setCategory(UUID id, String category) {
        return jdbc.sql("update product set category = ? where id = ?").params(category, id).update() == 1;
    }

    /** Subscription id -> the category a person set for its product; subscriptions without one are left out. */
    Map<UUID, String> categoriesOfSubscriptions() {
        Map<UUID, String> out = new HashMap<>();
        jdbc.sql("""
                select s.id, p.category from subscription s join product p on p.id = s.product_id
                where p.category is not null and p.category <> ''
                """).query((rs, n) -> out.put(rs.getObject("id", UUID.class), rs.getString("category"))).list();
        return out;
    }

    /** False when the same event was recorded before. */
    boolean recordActivity(UUID tenantId, UUID employeeId, UUID productId, UUID subscriptionId, Instant at,
            String kind, String source) {
        return jdbc.sql("""
                insert into activity_event (id, tenant_id, employee_id, product_id, subscription_id, occurred_at, kind,
                                            source)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, employee_id, product_id, occurred_at, kind) do nothing
                """).params(UUID.randomUUID(), tenantId, employeeId, productId, subscriptionId, Timestamp.from(at),
                kind, source).update() == 1;
    }

    List<ActivityEvent> activityOf(UUID subscriptionId, int limit) {
        return jdbc.sql("""
                select employee_id, occurred_at, kind, source from activity_event
                where product_id = (select product_id from subscription where id = ?)
                order by occurred_at desc, id limit ?
                """).params(subscriptionId, limit)
                .query((rs, n) -> new ActivityEvent(rs.getObject("employee_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("kind"), rs.getString("source")))
                .list();
    }
}
