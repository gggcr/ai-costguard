package ai.costguard.core.assets.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Equipment and its handover acts (ADR 0021). An act is a primary accounting document with the employee's full name
 * in it, signed on paper: it is kept for the accounting term even when the person asks to be erased, and the job
 * deletes it that long after the device came back. Assignments point at the employee by id only.
 */
@Component
class AssetPersonalData implements DataHolder {

    static final String ACT_BASIS = "Акт приёма-передачи является первичным учётным документом и хранится весь "
            + "учётный срок (402-ФЗ, ст. 29); его удалит задача хранения";

    private final JdbcClient jdbc;

    AssetPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("asset", "asset_assignment", "asset_act", "asset_act_counter");
    }

    private record Acts(long count, LocalDate lastReturn, boolean open) {
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        Acts acts = jdbc.sql("""
                select count(a.id) as n, max(s.returned_at) as last_return,
                       coalesce(bool_or(s.returned_at is null and a.id is not null), false) as open
                from asset_assignment s left join asset_act a on a.assignment_id = s.id
                where s.employee_id = ?
                """).param(subject.employeeId())
                .query((rs, n) -> new Acts(rs.getLong("n"), rs.getObject("last_return", LocalDate.class),
                        rs.getBoolean("open"))).single();
        if (acts.count() > 0) {
            // While a device is still out its term has not started: the act goes a term after the return.
            LocalDate until = acts.open() || acts.lastReturn() == null ? null
                    : acts.lastReturn().plus(retention.handoverActs());
            report.retained("assets.asset_act", acts.count(), ACT_BASIS, until);
        }
        long assignments = jdbc.sql("select count(*) from asset_assignment where employee_id = ?")
                .param(subject.employeeId()).query(Long.class).single();
        if (assignments > 0) {
            report.retained("assets.asset_assignment", assignments,
                    "Только идентификатор сотрудника; запись сотрудника обезличена", null);
        }
        long issued = jdbc.sql("select count(*) from asset_act where lower(issued_by) in (:emails)")
                .param("emails", subject.emails()).query(Long.class).single();
        if (issued > 0) {
            report.retained("assets.asset_act.issued_by", issued, ACT_BASIS, null);
        }
    }

    @Override
    public void expire(Retention retention, Report report) {
        report.deleted("assets.asset_act", jdbc.sql("""
                delete from asset_act a using asset_assignment s
                where s.id = a.assignment_id and s.returned_at is not null and s.returned_at < ?
                """).param(retention.sinceDay(retention.handoverActs())).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "assets", "asset_act", "asset_act_counter", "asset_assignment",
                "asset");
    }
}
