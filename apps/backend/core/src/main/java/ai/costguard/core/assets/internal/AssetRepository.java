package ai.costguard.core.assets.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class AssetRepository {

    private static final String SELECT = """
            select a.id, a.inventory_number, a.model, a.type, a.cost, a.serial_number, a.status,
                   o.id as assignment_id, o.employee_id, o.issued_at, o.return_due, t.number as act_number
            from asset a
            left join asset_assignment o on o.asset_id = a.id and o.returned_at is null
            left join asset_act t on t.assignment_id = o.id
            """;

    private static final RowMapper<Asset> ASSET = AssetRepository::asset;

    private static final RowMapper<Assignment> ASSIGNMENT = (rs, n) -> new Assignment(
            rs.getObject("id", UUID.class),
            rs.getObject("asset_id", UUID.class),
            rs.getObject("employee_id", UUID.class),
            rs.getObject("issued_at", LocalDate.class),
            rs.getObject("return_due", LocalDate.class),
            rs.getObject("returned_at", LocalDate.class),
            rs.getString("act_number"));

    private final JdbcClient jdbc;

    AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Asset asset(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID assignmentId = rs.getObject("assignment_id", UUID.class);
        Assignment open = assignmentId == null ? null : new Assignment(assignmentId, id,
                rs.getObject("employee_id", UUID.class), rs.getObject("issued_at", LocalDate.class),
                rs.getObject("return_due", LocalDate.class), null, rs.getString("act_number"));
        return new Asset(id, rs.getString("inventory_number"), rs.getString("model"),
                AssetType.valueOf(rs.getString("type")), rs.getBigDecimal("cost"), rs.getString("serial_number"),
                AssetStatus.valueOf(rs.getString("status")), open);
    }

    void insert(UUID tenantId, UUID id, NewAsset asset) {
        jdbc.sql("""
                insert into asset (id, tenant_id, inventory_number, model, type, cost, serial_number, status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, tenantId, asset.inventoryNumber().strip(), asset.model().strip(), asset.type().name(),
                        asset.cost(), asset.serialNumber() == null ? null : asset.serialNumber().strip(),
                        AssetStatus.IN_STOCK.name())
                .update();
    }

    Optional<Asset> find(UUID id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(ASSET).optional();
    }

    /** The asset a source names: by serial number first, then by inventory number. */
    Optional<UUID> findByNumbers(String serialNumber, String inventoryNumber) {
        Optional<UUID> bySerial = serialNumber == null ? Optional.empty()
                : jdbc.sql("select id from asset where serial_number = ?").param(serialNumber).query(UUID.class)
                        .optional();
        if (bySerial.isPresent() || inventoryNumber == null) {
            return bySerial;
        }
        return jdbc.sql("select id from asset where inventory_number = ?").param(inventoryNumber).query(UUID.class)
                .optional();
    }

    void updateReported(UUID id, String model, AssetType type, BigDecimal cost, String serialNumber) {
        jdbc.sql("""
                update asset set model = ?, type = ?, cost = ?, serial_number = coalesce(serial_number, ?)
                where id = ?
                """).params(model, type.name(), cost, serialNumber, id).update();
    }

    /**
     * Takes the row lock that serializes every state change of one asset. The state is read by a separate
     * statement: a locking read over the join would not see an assignment committed while it waited.
     */
    Optional<Asset> findForUpdate(UUID id) {
        boolean exists = jdbc.sql("select id from asset where id = ? for update").param(id)
                .query(UUID.class).optional().isPresent();
        return exists ? find(id) : Optional.empty();
    }

    List<Asset> list(AssetStatus status, int page, int size) {
        return jdbc.sql(SELECT + """
                 where (cast(:status as text) is null or a.status = :status)
                 order by a.id limit :size offset :offset
                """)
                .param("status", status == null ? null : status.name())
                .param("size", size)
                .param("offset", (long) page * size)
                .query(ASSET).list();
    }

    List<Asset> heldBy(UUID employeeId) {
        return jdbc.sql(SELECT + " where o.employee_id = ? order by a.id").param(employeeId).query(ASSET).list();
    }

    List<Assignment> history(UUID assetId) {
        return jdbc.sql("""
                select g.id, g.asset_id, g.employee_id, g.issued_at, g.return_due, g.returned_at,
                       t.number as act_number
                from asset_assignment g
                left join asset_act t on t.assignment_id = g.id
                where g.asset_id = ?
                order by g.issued_at desc, g.id
                """).param(assetId).query(ASSIGNMENT).list();
    }

    Optional<Assignment> assignment(UUID assetId, UUID assignmentId) {
        return history(assetId).stream().filter(a -> a.id().equals(assignmentId)).findFirst();
    }

    void setStatus(UUID assetId, AssetStatus status) {
        jdbc.sql("update asset set status = ? where id = ?").params(status.name(), assetId).update();
    }

    void insertAssignment(UUID tenantId, Assignment a) {
        jdbc.sql("""
                insert into asset_assignment (id, tenant_id, asset_id, employee_id, issued_at, return_due)
                values (?, ?, ?, ?, ?, ?)
                """)
                .params(a.id(), tenantId, a.assetId(), a.employeeId(), a.issuedAt(), a.returnDue())
                .update();
    }

    void moveReturnDate(UUID assignmentId, LocalDate returnDue) {
        jdbc.sql("update asset_assignment set return_due = ? where id = ?").params(returnDue, assignmentId).update();
    }

    void closeAssignment(UUID assignmentId, LocalDate returnedAt) {
        jdbc.sql("update asset_assignment set returned_at = ? where id = ?")
                .params(returnedAt, assignmentId).update();
    }
}
