package ai.costguard.core.software.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SoftwareRepository {

    private static final RowMapper<Vendor> VENDOR = (rs, n) -> new Vendor(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("inn"), rs.getString("category"));

    private static final RowMapper<Contract> CONTRACT = (rs, n) -> new Contract(rs.getObject("id", UUID.class),
            rs.getObject("vendor_id", UUID.class), rs.getString("number"), rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class), rs.getInt("notice_days"), rs.getBoolean("auto_renew"),
            rs.getBigDecimal("amount"));

    private static final String SUBSCRIPTION = """
            select s.id, s.vendor_id, s.product, s.contract_id, s.department_id, s.seats, s.price_per_seat,
                   s.billing_period, s.renewal_date,
                   (select count(*) from license_assignment l where l.subscription_id = s.id) as assigned
            from subscription s
            """;

    private static final RowMapper<Subscription> SUB = (rs, n) -> new Subscription(rs.getObject("id", UUID.class),
            rs.getObject("vendor_id", UUID.class), rs.getString("product"), rs.getObject("contract_id", UUID.class),
            rs.getObject("department_id", UUID.class), rs.getInt("seats"), rs.getBigDecimal("price_per_seat"),
            BillingPeriod.valueOf(rs.getString("billing_period")), rs.getObject("renewal_date", LocalDate.class),
            rs.getInt("assigned"));

    private static final RowMapper<License> LICENSE = (rs, n) -> new License(rs.getObject("id", UUID.class),
            rs.getObject("subscription_id", UUID.class), rs.getObject("employee_id", UUID.class),
            rs.getObject("last_active", LocalDate.class));

    private final JdbcClient jdbc;

    SoftwareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Vendor> vendors() {
        return jdbc.sql("select id, name, inn, category from vendor order by name").query(VENDOR).list();
    }

    Optional<Vendor> vendor(UUID id) {
        return jdbc.sql("select id, name, inn, category from vendor where id = ?").param(id).query(VENDOR).optional();
    }

    void insertVendor(UUID tenantId, UUID id, VendorDetails v) {
        jdbc.sql("insert into vendor (id, tenant_id, name, inn, category) values (?, ?, ?, ?, ?)")
                .params(id, tenantId, v.name(), v.inn(), v.category()).update();
    }

    boolean updateVendor(UUID id, VendorDetails v) {
        return jdbc.sql("update vendor set name = ?, inn = ?, category = ? where id = ?")
                .params(v.name(), v.inn(), v.category(), id).update() == 1;
    }

    List<Contract> contracts(UUID vendorId) {
        return jdbc.sql("""
                select * from contract where (cast(:vendor as uuid) is null or vendor_id = :vendor)
                order by end_date, id
                """).param("vendor", vendorId).query(CONTRACT).list();
    }

    Optional<Contract> contract(UUID id) {
        return jdbc.sql("select * from contract where id = ?").param(id).query(CONTRACT).optional();
    }

    void insertContract(UUID tenantId, UUID id, ContractDetails c) {
        jdbc.sql("""
                insert into contract (id, tenant_id, vendor_id, number, start_date, end_date, notice_days, auto_renew,
                                      amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, c.vendorId(), c.number(), c.startDate(), c.endDate(), c.noticeDays(),
                c.autoRenew(), c.amount()).update();
    }

    boolean updateContract(UUID id, ContractDetails c) {
        return jdbc.sql("""
                update contract set vendor_id = ?, number = ?, start_date = ?, end_date = ?, notice_days = ?,
                                    auto_renew = ?, amount = ?
                where id = ?
                """).params(c.vendorId(), c.number(), c.startDate(), c.endDate(), c.noticeDays(), c.autoRenew(),
                c.amount(), id).update() == 1;
    }

    List<Subscription> subscriptions() {
        return jdbc.sql(SUBSCRIPTION + " order by s.renewal_date, s.id").query(SUB).list();
    }

    Optional<Subscription> subscription(UUID id) {
        return jdbc.sql(SUBSCRIPTION + " where s.id = ?").param(id).query(SUB).optional();
    }

    /** Locks the subscription, so two assignments cannot both take the last seat. */
    Optional<Subscription> subscriptionForUpdate(UUID id) {
        boolean exists = jdbc.sql("select id from subscription where id = ? for update").param(id)
                .query(UUID.class).optional().isPresent();
        return exists ? subscription(id) : Optional.empty();
    }

    void insertSubscription(UUID tenantId, UUID id, SubscriptionDetails s) {
        jdbc.sql("""
                insert into subscription (id, tenant_id, vendor_id, product, contract_id, department_id, seats,
                                          price_per_seat, billing_period, renewal_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, s.vendorId(), s.product(), s.contractId(), s.departmentId(), s.seats(),
                s.pricePerSeat(), s.billingPeriod().name(), s.renewalDate()).update();
    }

    void updateSubscription(UUID id, SubscriptionDetails s) {
        jdbc.sql("""
                update subscription set vendor_id = ?, product = ?, contract_id = ?, department_id = ?, seats = ?,
                                        price_per_seat = ?, billing_period = ?, renewal_date = ?
                where id = ?
                """).params(s.vendorId(), s.product(), s.contractId(), s.departmentId(), s.seats(), s.pricePerSeat(),
                s.billingPeriod().name(), s.renewalDate(), id).update();
    }

    List<License> licenses(UUID subscriptionId) {
        return jdbc.sql("select * from license_assignment where subscription_id = ? order by employee_id")
                .param(subscriptionId).query(LICENSE).list();
    }

    Optional<License> license(UUID id) {
        return jdbc.sql("select * from license_assignment where id = ?").param(id).query(LICENSE).optional();
    }

    Optional<License> licenseOf(UUID subscriptionId, UUID employeeId) {
        return jdbc.sql("select * from license_assignment where subscription_id = ? and employee_id = ?")
                .params(subscriptionId, employeeId).query(LICENSE).optional();
    }

    void insertLicense(UUID tenantId, UUID id, UUID subscriptionId, UUID employeeId) {
        jdbc.sql("insert into license_assignment (id, tenant_id, subscription_id, employee_id) values (?, ?, ?, ?)")
                .params(id, tenantId, subscriptionId, employeeId).update();
    }

    boolean deleteLicense(UUID id) {
        return jdbc.sql("delete from license_assignment where id = ?").param(id).update() == 1;
    }

    boolean setActivity(UUID id, LocalDate lastActive) {
        return jdbc.sql("update license_assignment set last_active = greatest(coalesce(last_active, ?), ?) where id = ?")
                .params(lastActive, lastActive, id).update() == 1;
    }

    record Held(String vendor, String product, java.math.BigDecimal pricePerSeat) {
    }

    List<Held> heldBy(UUID employeeId) {
        return jdbc.sql("""
                select v.name as vendor, s.product, s.price_per_seat from license_assignment l
                join subscription s on s.id = l.subscription_id
                join vendor v on v.id = s.vendor_id
                where l.employee_id = ? order by v.name, s.product
                """).param(employeeId)
                .query((rs, n) -> new Held(rs.getString("vendor"), rs.getString("product"),
                        rs.getBigDecimal("price_per_seat")))
                .list();
    }
}
