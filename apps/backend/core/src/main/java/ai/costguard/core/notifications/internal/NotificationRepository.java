package ai.costguard.core.notifications.internal;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, Notification n) {
        jdbc.sql("insert into notification (id, tenant_id, at, text, link, level) values (?, ?, ?, ?, ?, ?)")
                .params(n.id(), tenantId, Timestamp.from(n.at()), n.text(), n.link(), n.level().name()).update();
    }

    void insertPersonal(UUID tenantId, UUID employeeId, Notification n) {
        jdbc.sql("""
                insert into notification (id, tenant_id, at, text, link, level, employee_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(n.id(), tenantId, Timestamp.from(n.at()), n.text(), n.link(), n.level().name(), employeeId)
                .update();
    }

    List<Notification> personal(UUID employeeId, int limit) {
        return jdbc.sql("""
                select id, at, text, link, level, read from notification
                where employee_id = ? order by at desc, id limit ?""")
                .params(employeeId, limit)
                .query((rs, i) -> new Notification(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getString("text"), rs.getString("link"), Level.valueOf(rs.getString("level")),
                        rs.getBoolean("read")))
                .list();
    }

    int unreadPersonal(UUID employeeId) {
        return jdbc.sql("select count(*) from notification where employee_id = ? and not read").param(employeeId)
                .query(Integer.class).single();
    }

    boolean markReadPersonal(UUID employeeId, UUID id) {
        return jdbc.sql("update notification set read = true where id = ? and employee_id = ?")
                .params(id, employeeId).update() == 1;
    }

    void markAllReadPersonal(UUID employeeId) {
        jdbc.sql("update notification set read = true where employee_id = ? and not read").param(employeeId).update();
    }

    int deletePersonal(UUID employeeId) {
        return jdbc.sql("delete from notification where employee_id = ?").param(employeeId).update();
    }

    /** The company's feed: notifications of one employee stay out of it. */
    List<Notification> latest(int limit) {
        return jdbc.sql("""
                select id, at, text, link, level, read from notification where employee_id is null
                order by at desc, id limit ?""")
                .param(limit)
                .query((rs, i) -> new Notification(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getString("text"), rs.getString("link"), Level.valueOf(rs.getString("level")),
                        rs.getBoolean("read")))
                .list();
    }

    int unread() {
        return jdbc.sql("select count(*) from notification where employee_id is null and not read")
                .query(Integer.class).single();
    }

    boolean markRead(UUID id) {
        return jdbc.sql("update notification set read = true where id = ? and employee_id is null").param(id)
                .update() == 1;
    }

    void markAllRead() {
        jdbc.sql("update notification set read = true where employee_id is null and not read").update();
    }

    void clear() {
        jdbc.sql("delete from notification where employee_id is null").update();
    }
}
