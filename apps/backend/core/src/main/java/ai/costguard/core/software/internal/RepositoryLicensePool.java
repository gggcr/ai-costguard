package ai.costguard.core.software.internal;

import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.LicensePool;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class RepositoryLicensePool implements LicensePool {

    private static final RowMapper<Offer> OFFER = (rs, i) -> new Offer(rs.getObject("id", UUID.class),
            rs.getString("vendor"), rs.getString("product"), rs.getInt("seats"), rs.getInt("assigned"),
            Math.max(0, rs.getInt("seats") - rs.getInt("assigned")), rs.getInt("idle"));

    private static final RowMapper<Idle> IDLE = (rs, i) -> new Idle(rs.getObject("id", UUID.class),
            rs.getObject("subscription_id", UUID.class), rs.getObject("employee_id", UUID.class),
            rs.getString("product"), rs.getObject("last_active", LocalDate.class));

    private final JdbcClient jdbc;
    private final SoftwareService software;
    private final Clock clock;

    RepositoryLicensePool(JdbcClient jdbc, SoftwareService software, Clock clock) {
        this.jdbc = jdbc;
        this.software = software;
        this.clock = clock;
    }

    private LocalDate since(int idleDays) {
        return LocalDate.now(clock).minusDays(idleDays);
    }

    @Override
    public List<Held> heldBy(UUID employeeId) {
        return jdbc.sql("""
                select l.id, l.subscription_id, v.name as vendor, s.product, l.last_active
                from license_assignment l join subscription s on s.id = l.subscription_id
                join vendor v on v.id = s.vendor_id
                where l.employee_id = ? order by s.product""").param(employeeId)
                .query((rs, i) -> new Held(rs.getObject("id", UUID.class), rs.getObject("subscription_id", UUID.class),
                        rs.getString("vendor"), rs.getString("product"), rs.getObject("last_active", LocalDate.class)))
                .list();
    }

    private static final String OFFERS = """
            select s.id, v.name as vendor, s.product, s.seats,
                   (select count(*) from license_assignment l where l.subscription_id = s.id) as assigned,
                   (select count(*) from license_assignment l where l.subscription_id = s.id
                      and (l.last_active is null or l.last_active < :since)) as idle
            from subscription s join vendor v on v.id = s.vendor_id""";

    @Override
    public List<Offer> offers(int idleDays) {
        return jdbc.sql(OFFERS + " order by s.product").param("since", since(idleDays)).query(OFFER).list();
    }

    @Override
    public Optional<Offer> offer(UUID subscriptionId, int idleDays) {
        return jdbc.sql(OFFERS + " where s.id = :id").param("since", since(idleDays)).param("id", subscriptionId)
                .query(OFFER).optional();
    }

    /**
     * Checked here, not by catching SEATS_EXHAUSTED: a failure inside the joined transaction of
     * {@link SoftwareService#assign} would mark the caller's transaction rollback-only.
     */
    @Override
    public Optional<UUID> grant(UUID subscriptionId, UUID employeeId) {
        var row = jdbc.sql("""
                select s.tenant_id, s.seats,
                       (select count(*) from license_assignment l where l.subscription_id = s.id) as assigned,
                       (select l.id from license_assignment l where l.subscription_id = s.id and l.employee_id = ?)
                           as held
                from subscription s where s.id = ? for update of s""").params(employeeId, subscriptionId)
                .query((rs, i) -> new Object[] {rs.getObject("tenant_id", UUID.class), rs.getInt("seats"),
                        rs.getInt("assigned"), rs.getObject("held", UUID.class)})
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = row.get();
        if (r[3] != null) {
            return Optional.of((UUID) r[3]);
        }
        if ((int) r[2] >= (int) r[1]) {
            return Optional.empty();
        }
        return Optional.of(software.assign((UUID) r[0], subscriptionId, employeeId).id());
    }

    @Override
    public Optional<Idle> idlest(UUID subscriptionId, int idleDays, UUID except) {
        return jdbc.sql("""
                select l.id, l.subscription_id, l.employee_id, s.product, l.last_active
                from license_assignment l join subscription s on s.id = l.subscription_id
                where l.subscription_id = ? and l.employee_id <> ? and (l.last_active is null or l.last_active < ?)
                order by l.last_active nulls first limit 1""").params(subscriptionId, except, since(idleDays))
                .query(IDLE).optional();
    }

    @Override
    public List<Idle> idle(int idleDays) {
        return jdbc.sql("""
                select l.id, l.subscription_id, l.employee_id, s.product, l.last_active
                from license_assignment l join subscription s on s.id = l.subscription_id
                where l.last_active < ? order by s.product, l.last_active""").param(since(idleDays))
                .query(IDLE).list();
    }

    @Override
    public boolean reclaim(UUID licenseId) {
        return jdbc.sql("delete from license_assignment where id = ?").param(licenseId).update() == 1;
    }

    @Override
    public Optional<Offer> match(String text, int idleDays) {
        String wanted = Names.clean(text);
        if (wanted.isBlank()) {
            return Optional.empty();
        }
        List<Offer> found = offers(idleDays).stream().filter(o -> {
            String product = Names.clean(o.product());
            String vendor = Names.clean(o.vendor());
            return product.equals(wanted) || vendor.equals(wanted) || (!product.isBlank()
                    && (wanted.contains(product) || product.contains(wanted)));
        }).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }
}
