package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Зарплата (ADR 0054): finance loads, the employee sees only theirs and is told on the pay day. */
@SpringBootTest
@AutoConfigureMockMvc
class PayrollIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired PayrollService payroll;
    @Autowired PlatformTransactionManager transactions;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor finance() {
        return as("fin@corp.ru", "finance");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String employee(String name, String email) throws Exception {
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                        "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(as("o@corp.ru", "owner")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(json(post("/api/v1/employees"),
                        "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                                .formatted(name, email, department)).with(as("o@corp.ru", "owner")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aPaySheetGoesToEachEmployeeAndOnlyToThem() throws Exception {
        String anna = "anna-%s@corp.ru".formatted(UUID.randomUUID());
        String oleg = "oleg-%s@corp.ru".formatted(UUID.randomUUID());
        employee("Смирнова Анна Петровна", anna);
        employee("Кузнецов Олег", oleg);
        String csv = """
                Сотрудник;Вид выплаты;Период;Дата выплаты;Сумма
                %s;Аванс;01.10.2026;24.10.2026;40 000,00
                Кузнецов Олег;Зарплата за месяц;2026-10;10.11.2026;61500.50
                Неизвестный Человек;Зарплата;2026-10;10.11.2026;1000
                %s;Зарплата;2026-10;не дата;1000
                """.formatted(anna, anna);
        mvc.perform(multipart("/api/v1/payroll/imports").file(new MockMultipartFile("file", "ведомость.csv",
                        "text/csv", csv.getBytes(StandardCharsets.UTF_8))).with(finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].row").value(5))
                .andExpect(jsonPath("$.skipped[1].row").value(4));

        mvc.perform(get("/api/v1/me/payroll").with(as(anna, "employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].kind").value("advance"))
                .andExpect(jsonPath("$.payments[0].amount").value(40000.00))
                .andExpect(jsonPath("$.upcoming.length()").value(2));
        mvc.perform(get("/api/v1/me/payroll").with(as(oleg, "employee")))
                .andExpect(jsonPath("$.payments[0].kind").value("salary"))
                .andExpect(jsonPath("$.payments[0].amount").value(61500.50));
        // owner and finance see everyone; it_admin and the employees do not
        mvc.perform(get("/api/v1/payroll/payments").param("month", "2026-10").with(finance()))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/payroll/payments").param("month", "2026-10").with(as("it@corp.ru", "it_admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payroll/payments").param("month", "2026-10").with(as(anna, "employee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theConnectorSendsPaymentsAndTheSamePaymentAgainReplacesIt() throws Exception {
        String ivan = "ivan-%s@corp.ru".formatted(UUID.randomUUID());
        employee("Иванов Иван", ivan);
        String batch = """
                {"source":"1c-zup","payments":[{"employeeName":"Иванов Иван","kind":"salary","period":"2026-10-01",
                 "payDate":"2026-11-10","amount":%s,"externalKey":"doc-1:emp-1"}]}""";
        mvc.perform(json(post("/api/v1/payroll/payments"), batch.formatted("70000")).with(as("c@x", "connector")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(1));
        mvc.perform(json(post("/api/v1/payroll/payments"), batch.formatted("71000")).with(as("c@x", "connector")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/payroll").with(as(ivan, "employee")))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].amount").value(71000.00))
                .andExpect(jsonPath("$.payments[0].source").value("1c-zup"));
        mvc.perform(json(post("/api/v1/payroll/payments"), batch.formatted("1")).with(as(ivan, "employee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void onThePayDayTheEmployeeIsToldOnce() throws Exception {
        String maria = "maria-%s@corp.ru".formatted(UUID.randomUUID());
        employee("Петрова Мария", maria);
        String batch = """
                {"source":"manual","payments":[{"employeeEmail":"%s","kind":"advance","period":"%s",
                 "payDate":"%s","amount":25000}]}""".formatted(maria, LocalDate.now(), LocalDate.now().minusDays(1));
        mvc.perform(json(post("/api/v1/payroll/payments"), batch).with(finance())).andExpect(status().isOk());

        var tx = new TransactionTemplate(transactions);
        Integer first = tx.execute(s -> payroll.tellDue(tenant));
        Integer again = tx.execute(s -> payroll.tellDue(tenant));
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        mvc.perform(get("/api/v1/me/notifications").with(as(maria, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(org.hamcrest.Matchers.startsWith("Аванс ")))
                .andExpect(jsonPath("$.items[0].text").value(org.hamcrest.Matchers.containsString("25 000,00 ₽")));
    }

    @Test
    void thePayDaysFollowTheScheduleAndSkipWeekends() throws Exception {
        String e = "e-%s@corp.ru".formatted(UUID.randomUUID());
        employee("Сотрудник Расписания", e);
        mvc.perform(json(put("/api/v1/payroll/schedule"), "{\"advanceDay\":20,\"salaryDay\":5}").with(finance()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/payroll").with(as(e, "employee")))
                .andExpect(jsonPath("$.schedule.advanceDay").value(20))
                .andExpect(jsonPath("$.schedule.salaryDay").value(5));
        // 20.12.2026 is a Sunday: the advance is paid on Friday the 18th
        var dates = PayrollService.upcoming(new PayrollSchedule(20, 5), LocalDate.of(2026, 12, 1));
        assertThat(dates).extracting(PayDate::date)
                .containsExactly(LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 18));
    }

    @Test
    void anXlsxSheetIsReadToo() {
        byte[] xlsx = Xlsx.of(List.of(
                List.of("ФИО", "Вид", "Дата выплаты", "Сумма"),
                List.of("Иванов Иван", "Премия", "46336", "15000")));
        var rows = PayrollFile.read("премии.xlsx", xlsx);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).person()).isEqualTo("Иванов Иван");
        assertThat(PayrollFile.kind(rows.get(0).kind())).isEqualTo(PayKind.bonus);
        assertThat(PayrollFile.date(rows.get(0).payDate())).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(PayrollFile.amount(rows.get(0).amount())).isEqualByComparingTo("15000");
    }

    /** A minimal .xlsx: shared strings for text, numbers inline. */
    static final class Xlsx {
        static byte[] of(List<List<String>> rows) {
            var strings = new java.util.ArrayList<String>();
            var sheet = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                sheet.append("<row r=\"").append(r + 1).append("\">");
                for (int c = 0; c < rows.get(r).size(); c++) {
                    String v = rows.get(r).get(c);
                    String ref = (char) ('A' + c) + String.valueOf(r + 1);
                    if (v.matches("\\d+")) {
                        sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                    } else {
                        strings.add(v);
                        sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(strings.size() - 1)
                                .append("</v></c>");
                    }
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            var shared = new StringBuilder("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            strings.forEach(s -> shared.append("<si><t>").append(s).append("</t></si>"));
            shared.append("</sst>");
            var out = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/sharedStrings.xml"));
                zip.write(shared.toString().getBytes(StandardCharsets.UTF_8));
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
                zip.write(sheet.toString().getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return out.toByteArray();
        }
    }
}
