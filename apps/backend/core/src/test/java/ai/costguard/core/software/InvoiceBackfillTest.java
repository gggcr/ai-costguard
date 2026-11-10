package ai.costguard.core.software;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V25 copies what ingest_entity already holds into invoice and payment. Flyway has run that copy on an empty table by
 * now, so this runs it again on rows of a company of its own, as the owner of the tables, like Flyway does.
 */
@SpringBootTest
class InvoiceBackfillTest extends PostgresTestSupport {

    final UUID tenant = UUID.randomUUID();
    final UUID vendor = UUID.randomUUID();
    final UUID subscription = UUID.randomUUID();
    final JdbcClient superuser = JdbcClient.create(superuserAppDataSource());

    void entity(String key, String type, String payload, UUID linked) {
        superuser.sql("""
                insert into ingest_entity (tenant_id, entity_key, entity_type, source_id, payload, confidence,
                                           linked_id, first_seen, last_seen)
                values (?, ?, ?, ?, cast(? as jsonb), 1, ?, timestamptz '2026-10-20 10:00+03', now())
                """).params(tenant, tenant + ":" + key, type, UUID.randomUUID(), payload, linked).update();
    }

    @Test
    void invoicesAndPaymentsAlreadyReportedAreCopied() throws Exception {
        superuser.sql("insert into vendor (id, tenant_id, name) values (?, ?, 'Атлассиан')").params(vendor, tenant)
                .update();
        superuser.sql("""
                insert into subscription (id, tenant_id, vendor_id, product, department_id, seats, price_per_seat,
                                          billing_period, renewal_date)
                values (?, ?, ?, 'Jira', ?, 3, 1000, 'month', '2027-01-01')
                """).params(subscription, tenant, vendor, UUID.randomUUID()).update();
        entity("invoice:mail:a-1", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-1","total":"3000","currency":"RUB","date":"2026-10-05","source":"mail"}""",
                vendor);
        entity("invoice:file:x", "INVOICE", """
                {"file_name":"счёт.pdf","vendor":"Атлассиан","total":"1200.50"}""", vendor);
        entity("invoice:file:bad", "INVOICE", "{\"vendor\":\"Атлассиан\",\"total\":\"много\"}", vendor);
        entity("invoice:file:unlinked", "INVOICE", "{\"vendor\":\"Никто\",\"total\":\"10\"}", null);
        entity("payment:bank:1", "PAYMENT", """
                {"vendor":"Атлассиан","amount":"3000","currency":"rub","date":"2026-10-07","kind":"CARD",
                 "card":"4276****1234","mcc":"5734"}""", vendor);

        String migration = Files.readString(Path.of(System.getProperty("repo.root"),
                "apps/backend/core/src/main/resources/db/migration/V25__invoices_and_payments.sql"),
                StandardCharsets.UTF_8);
        String copy = migration.substring(migration.indexOf("alter table ingest_entity no force row level security;"));
        JdbcClient.create(appDataSource()).sql(copy).update();

        List<Map<String, Object>> invoices = superuser.sql("""
                select number, issued_on::text as day, dated, currency, total::text as total, source,
                       subscription_id
                from invoice where tenant_id = ? order by number desc
                """).param(tenant).query().listOfRows();
        assertThat(invoices).hasSize(2);
        assertThat(invoices.get(0)).containsEntry("number", "A-1").containsEntry("day", "2026-10-05")
                .containsEntry("dated", true).containsEntry("total", "3000.00")
                .containsEntry("subscription_id", subscription);
        // no date: the day it arrived, and it says so
        assertThat(invoices.get(1)).containsEntry("number", "").containsEntry("day", "2026-10-20")
                .containsEntry("dated", false).containsEntry("source", "счёт.pdf").containsEntry("currency", "RUB");

        Map<String, Object> payment = superuser.sql("""
                select amount::text as amount, currency, paid_on::text as day, kind, card, mcc from payment
                where tenant_id = ?
                """).param(tenant).query().singleRow();
        assertThat(payment).containsEntry("amount", "3000.00").containsEntry("currency", "RUB")
                .containsEntry("day", "2026-10-07").containsEntry("kind", "CARD").containsEntry("mcc", "5734");

        // the owner is held by the policies again
        Boolean forced = superuser.sql("select relforcerowsecurity from pg_class where relname = 'invoice'")
                .query(Boolean.class).single();
        assertThat(forced).isTrue();
    }
}
