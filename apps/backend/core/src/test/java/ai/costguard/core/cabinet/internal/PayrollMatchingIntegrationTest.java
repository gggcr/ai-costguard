package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Finding R1 of the audit of 2026-11-12: a pay sheet names an employee by e-mail, and the core took one of the
 * employees whose address matched it ignoring case ({@code lower(email) = ? ... limit 1}), so a second employee with
 * the same address in another case (an it_admin may make one, with a login whose password they are shown) could
 * receive someone's pay. Now a payment goes only to the one employee of the company whose address it names; an
 * ambiguous or unknown row is assigned to nobody and kept, with its reason, for finance to sort out.
 *
 * <p>Every test checks to whom the payments actually went. All data is fictitious.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayrollMatchingIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor as(UUID tenantId, String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenantId, email)).claim("tenant_id", tenantId.toString())
                        .claim("email", email).claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor owner(UUID tenantId) {
        return as(tenantId, "owner-" + run + "@payroll.example", "owner");
    }

    private RequestPostProcessor finance(UUID tenantId) {
        return as(tenantId, "fin-" + run + "@payroll.example", "finance");
    }

    private MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> request, RequestPostProcessor who)
            throws Exception {
        return mvc.perform(request.with(who)).andReturn().getResponse();
    }

    private static String body(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private String department(UUID tenantId) throws Exception {
        MockHttpServletResponse r = call(post("/api/v1/departments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Отдел %s\"}".formatted(UUID.randomUUID())), owner(tenantId));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.id");
    }

    private String employee(UUID tenantId, String name, String email) throws Exception {
        MockHttpServletResponse r = call(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(name, email, department(tenantId))), owner(tenantId));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.id");
    }

    private void dismissed(String employeeId) {
        su.sql("update employee set dismissed_at = date '2026-09-30' where id = ?").param(UUID.fromString(employeeId))
                .update();
    }

    private MockHttpServletResponse sheet(UUID tenantId, String... rows) throws Exception {
        String csv = "Сотрудник;Вид выплаты;Период;Дата выплаты;Сумма\n" + String.join("\n", rows) + "\n";
        return call(multipart("/api/v1/payroll/imports").file(new MockMultipartFile("file", "vedomost.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8))), finance(tenantId));
    }

    private MockHttpServletResponse batch(UUID tenantId, String paymentsJson) throws Exception {
        return call(post("/api/v1/payroll/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"1c-zup\",\"payments\":[" + paymentsJson + "]}"),
                as(tenantId, "kc-connector-" + run + "@payroll.example", "connector"));
    }

    /** The amounts of the month by employee, as finance sees them. */
    private List<Map<String, Object>> payments(UUID tenantId) throws Exception {
        MockHttpServletResponse r = call(get("/api/v1/payroll/payments").param("month", "2026-10"), finance(tenantId));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        return JsonPath.read(body(r), "$[*]");
    }

    private List<Object> amountsOf(UUID tenantId, String employeeId) throws Exception {
        return payments(tenantId).stream().filter(p -> employeeId.equals(p.get("employeeId")))
                .map(p -> p.get("amount")).toList();
    }

    private List<Map<String, Object>> unmatched(UUID tenantId) throws Exception {
        MockHttpServletResponse r = call(get("/api/v1/payroll/unmatched"), finance(tenantId));
        assertThat(r.getStatus()).as("finance sees the rows nobody got: %s", body(r)).isEqualTo(200);
        return JsonPath.read(body(r), "$[*]");
    }

    // R1.1

    @Test
    void r1_1_anAddressOfTwoWorkingEmployeesPaysNeitherAndIsKeptWithTheReason() throws Exception {
        String lower = employee(tenant, "Сотрудник Строчный", "user-" + run + "@example.com");
        String upper = employee(tenant, "Сотрудник Прописной", "User-" + run + "@example.com");

        MockHttpServletResponse r = sheet(tenant, "user-" + run + "@example.com;Аванс;2026-10;24.10.2026;31313,13");
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        assertThat(amountsOf(tenant, lower)).as("paid to one of two by chance").isEmpty();
        assertThat(amountsOf(tenant, upper)).as("paid to one of two by chance").isEmpty();
        assertThat((Integer) JsonPath.read(body(r), "$.stored")).isZero();
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");
        assertThat(JsonPath.<List<Integer>>read(body(r), "$.skipped[*].row")).containsExactly(2);

        List<Map<String, Object>> kept = unmatched(tenant);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).containsEntry("reason", "ambiguous employee").containsEntry("candidates", 2)
                .containsEntry("reportedEmail", "user-" + run + "@example.com").containsEntry("amount", 31313.13)
                .containsEntry("kind", "advance").containsEntry("payDate", "2026-10-24")
                .containsEntry("source", "excel:vedomost.csv");
    }

    // R1.2

    @Test
    void r1_2_aWorkingAndADismissedEmployeeWithOneAddressPayNeitherButADismissedOneAloneIsPaid() throws Exception {
        String working = employee(tenant, "Сотрудник Работает", "twin-" + run + "@example.com");
        String gone = employee(tenant, "Сотрудник Уволен", "Twin-" + run + "@example.com");
        dismissed(gone);
        MockHttpServletResponse r = sheet(tenant, "Twin-" + run + "@example.com;Зарплата;2026-10;10.10.2026;42424,24");
        // the schema keeps addresses unique as typed, so two records differing in case are never a feature: a new
        // working one must not take the final pay of a dismissed person, nor the other way round
        assertThat(amountsOf(tenant, working)).isEmpty();
        assertThat(amountsOf(tenant, gone)).isEmpty();
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");

        // a dismissed person alone with the address still gets the final settlement, as before
        String leaver = employee(tenant, "Сотрудник Расчёт", "leaver-" + run + "@example.com");
        dismissed(leaver);
        sheet(tenant, "leaver-" + run + "@example.com;Зарплата;2026-10;10.10.2026;1000,00");
        assertThat(amountsOf(tenant, leaver)).containsExactly(1000.0);
    }

    // R1.3

    @Test
    void r1_3_theSameAddressTwiceIsRefusedByTheSchemaAndByTheApi() throws Exception {
        String mail = "same-" + run + "@example.com";
        String first = employee(tenant, "Сотрудник Первый", mail);
        MockHttpServletResponse second = call(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"Второй\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                        .formatted(mail, department(tenant))), owner(tenant));
        assertThat(second.getStatus()).as(body(second)).isEqualTo(409);
        assertThatThrownBy(() -> su.sql("""
                insert into employee (id, tenant_id, department_id, name, email, position)
                select ?, tenant_id, department_id, 'Второй', email, 'x' from employee where id = ?""")
                .params(UUID.randomUUID(), UUID.fromString(first)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        sheet(tenant, mail + ";Аванс;2026-10;24.10.2026;100,00");
        assertThat(amountsOf(tenant, first)).containsExactly(100.0);
    }

    // R1.4

    @Test
    void r1_4_anAddressInTwoCompaniesPaysOnlyTheCompanyOfTheSheet() throws Exception {
        UUID other = UUID.randomUUID();
        String mail = "shared-" + run + "@example.com";
        String ours = employee(tenant, "Сотрудник Наш", mail);
        String theirs = employee(other, "Сотрудник Чужой", mail);
        String onlyTheirs = employee(other, "Сотрудник Только Чужой", "only-other-" + run + "@example.com");

        sheet(tenant, mail + ";Аванс;2026-10;24.10.2026;777,00");
        assertThat(amountsOf(tenant, ours)).containsExactly(777.0);
        assertThat(amountsOf(other, theirs)).as("the other company got our payment").isEmpty();

        MockHttpServletResponse r = sheet(tenant, "only-other-" + run + "@example.com;Аванс;2026-10;24.10.2026;888,00");
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(amountsOf(other, onlyTheirs)).as("a sheet of ours paid their employee").isEmpty();
        assertThat(body(r)).doesNotContain(onlyTheirs).doesNotContain("Только Чужой");
        // each company sees only its own rows nobody got
        assertThat(unmatched(tenant)).extracting(u -> u.get("reportedEmail"))
                .containsExactly("only-other-" + run + "@example.com");
        assertThat(unmatched(other)).isEmpty();
    }

    // R1.5

    @Test
    void r1_5_theOnlyWorkingEmployeeWithTheAddressIsPaid() throws Exception {
        String solo = employee(tenant, "Сотрудник Один", "solo-" + run + "@example.com");
        MockHttpServletResponse r = sheet(tenant, "solo-" + run + "@example.com;Премия;2026-10;15.10.2026;5000,00");
        assertThat((Integer) JsonPath.read(body(r), "$.stored")).isEqualTo(1);
        assertThat(amountsOf(tenant, solo)).containsExactly(5000.0);
        assertThat(unmatched(tenant)).isEmpty();
    }

    // R1.6

    @Test
    void r1_6_importingAgainNeitherDuplicatesNorPicksAndARightPaymentStays() throws Exception {
        String a = employee(tenant, "Сотрудник А", "again-" + run + "@example.com");
        // paid while the address was unambiguous
        sheet(tenant, "again-" + run + "@example.com;Аванс;2026-10;24.10.2026;1500,00");
        assertThat(amountsOf(tenant, a)).containsExactly(1500.0);
        // a second employee with the address in another case appears
        String twin = employee(tenant, "Сотрудник Двойник", "AGAIN-" + run + "@example.com");
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse r = sheet(tenant, "again-" + run + "@example.com;Аванс;2026-10;24.10.2026;9999,00");
            assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");
        }
        assertThat(amountsOf(tenant, a)).as("the right payment was overwritten").containsExactly(1500.0);
        assertThat(amountsOf(tenant, twin)).isEmpty();
        assertThat(unmatched(tenant)).as("one kept row, not one per import").hasSize(1);

        // finance sorts it out (the twin's address is corrected) and loads the sheet again: A is paid, the row is gone
        su.sql("update employee set email = ? where id = ?").params("twin-fixed-" + run + "@example.com",
                UUID.fromString(twin)).update();
        MockHttpServletResponse fixed = sheet(tenant, "again-" + run + "@example.com;Аванс;2026-10;24.10.2026;9999,00");
        assertThat((Integer) JsonPath.read(body(fixed), "$.stored")).isEqualTo(1);
        assertThat(amountsOf(tenant, a)).containsExactly(9999.0);
        assertThat(amountsOf(tenant, twin)).isEmpty();
        assertThat(unmatched(tenant)).isEmpty();
    }

    // R1.7

    @Test
    void r1_7_addressesAreComparedAsTheApplicationNormalisesThem() throws Exception {
        String mixed = employee(tenant, "Сотрудник Регистр", "case-" + run + "@example.com");
        // spaces around and another case in the sheet: the sheet's address is trimmed and lowered, as before
        MockHttpServletResponse r = sheet(tenant, "  CASE-" + run + "@Example.COM ;Аванс;2026-10;24.10.2026;300,00");
        assertThat((Integer) JsonPath.read(body(r), "$.stored")).isEqualTo(1);
        assertThat(amountsOf(tenant, mixed)).containsExactly(300.0);

        // a stored address with spaces is not trimmed (nothing in the application trims stored addresses)
        UUID padded = UUID.randomUUID();
        su.sql("""
                insert into employee (id, tenant_id, department_id, name, email, position)
                values (?, ?, ?, 'Сотрудник Пробел', ?, 'x')""")
                .params(padded, tenant, UUID.fromString(department(tenant)), " pad-" + run + "@example.com ").update();
        MockHttpServletResponse p = sheet(tenant, "pad-" + run + "@example.com;Аванс;2026-10;24.10.2026;400,00");
        assertThat(JsonPath.<List<String>>read(body(p), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(amountsOf(tenant, padded.toString())).isEmpty();

        // not an address at all
        MockHttpServletResponse bad = sheet(tenant, "not-an-address@;Аванс;2026-10;24.10.2026;500,00");
        assertThat(JsonPath.<List<String>>read(body(bad), "$.skipped[*].reason")).containsExactly("bad email");
        assertThat(unmatched(tenant)).extracting(u -> u.get("reason"))
                .containsExactlyInAnyOrder("employee not found", "bad email");
    }

    // the connector: an ambiguous address is not rescued by the name

    @Test
    void anAmbiguousAddressFromTheConnectorIsNotMatchedByTheNameEither() throws Exception {
        String named = employee(tenant, "Сотрудник Уникальный Именем", "conn-" + run + "@example.com");
        employee(tenant, "Сотрудник Другой", "Conn-" + run + "@example.com");
        MockHttpServletResponse r = batch(tenant, """
                {"employeeEmail":"conn-%s@example.com","employeeName":"Сотрудник Уникальный Именем","kind":"salary",
                 "period":"2026-10-01","payDate":"2026-10-10","amount":64000,"externalKey":"zup-1"}""".formatted(run));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");
        assertThat(amountsOf(tenant, named)).isEmpty();
        assertThat(unmatched(tenant)).singleElement().satisfies(u -> assertThat(u)
                .containsEntry("reportedName", "Сотрудник Уникальный Именем").containsEntry("externalKey", "zup-1")
                .containsEntry("source", "1c-zup"));
        // an unknown address is not rescued by the name either (R1.5.1): it pays nobody
        MockHttpServletResponse byName = batch(tenant, """
                {"employeeEmail":"nobody-%s@example.com","employeeName":"Сотрудник Уникальный Именем","kind":"bonus",
                 "period":"2026-10-01","payDate":"2026-10-20","amount":1000,"externalKey":"zup-2"}""".formatted(run));
        assertThat((Integer) JsonPath.read(body(byName), "$.stored")).isZero();
        assertThat(JsonPath.<List<String>>read(body(byName), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(amountsOf(tenant, named)).isEmpty();
    }

    // R1.5.1: an address that does not name the employee is never rescued by a name

    /** An employee with their own CostGuard account, so their own pay can be read the way they read it. */
    private String employeeWithLogin(String name, String email) throws Exception {
        String id = employee(tenant, name, email);
        login(tenant, email, "employee", UUID.fromString(id));
        return id;
    }

    private String myPay(String email) throws Exception {
        MockHttpServletResponse r = call(get("/api/v1/me/payroll"), as(tenant, email, "employee"));
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        return body(r);
    }

    private static String connectorPayment(String email, String name, String key, String amount) {
        String address = email == null ? "" : "\"employeeEmail\":\"" + email + "\",";
        return "{" + address + "\"employeeName\":\"" + name + "\",\"kind\":\"bonus\",\"period\":\"2026-10-01\","
                + "\"payDate\":\"2026-10-21\",\"amount\":" + amount + ",\"externalKey\":\"" + key + "\"}";
    }

    @Test
    void r1_5_1_aBrokenAddressWithSomeoneElsesNamePaysNobody() throws Exception {
        String mail = "named-" + run + "@example.com";
        String named = employeeWithLogin("Сотрудник Чужое Имя", mail);
        MockHttpServletResponse r = batch(tenant, connectorPayment("named-" + run + "@@example", "Сотрудник Чужое Имя",
                "zup-bad", "12121"));
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("bad email");
        assertThat(amountsOf(tenant, named)).isEmpty();
        assertThat(myPay(mail)).doesNotContain("12121");
        assertThat(unmatched(tenant)).singleElement().satisfies(u -> assertThat(u).containsEntry("reason", "bad email")
                .containsEntry("reportedName", "Сотрудник Чужое Имя"));
    }

    @Test
    void r1_5_1_aValidButUnknownAddressWithAnExistingNamePaysNobody() throws Exception {
        String mail = "existing-" + run + "@example.com";
        String named = employeeWithLogin("Сотрудник Существующий", mail);
        MockHttpServletResponse r = batch(tenant, connectorPayment("typo-" + run + "@example.com",
                "Сотрудник Существующий", "zup-typo", "34343"));
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(amountsOf(tenant, named)).isEmpty();
        assertThat(myPay(mail)).doesNotContain("34343");
        // a file row with that unknown address
        MockHttpServletResponse sheet = sheet(tenant, "typo-" + run + "@example.com;Премия;2026-10;21.10.2026;34343,00");
        assertThat(JsonPath.<List<String>>read(body(sheet), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(myPay(mail)).doesNotContain("34343");
    }

    @Test
    void r1_5_1_noAddressAndAUniqueNamePaysThatOne() throws Exception {
        String mail = "only-name-" + run + "@example.com";
        String named = employeeWithLogin("Сотрудник Единственный Такой", mail);
        MockHttpServletResponse r = batch(tenant, connectorPayment(null, "Сотрудник Единственный Такой", "zup-name",
                "5656"));
        assertThat((Integer) JsonPath.read(body(r), "$.stored")).isEqualTo(1);
        assertThat(amountsOf(tenant, named)).containsExactly(5656.0);
        assertThat(myPay(mail)).contains("5656");
        // a file row with the name only, too
        sheet(tenant, "Сотрудник Единственный Такой;Аванс;2026-10;24.10.2026;7070,00");
        assertThat(amountsOf(tenant, named)).containsExactlyInAnyOrder(5656.0, 7070.0);
    }

    @Test
    void r1_5_1_noAddressAndNamesakesPayNobody() throws Exception {
        String first = "twin-name-1-" + run + "@example.com";
        String second = "twin-name-2-" + run + "@example.com";
        String a = employeeWithLogin("Сотрудник Тёзка", first);
        String b = employeeWithLogin("Сотрудник Тёзка", second);
        MockHttpServletResponse r = batch(tenant, connectorPayment(null, "Сотрудник Тёзка", "zup-namesake", "8989"));
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");
        assertThat(amountsOf(tenant, a)).isEmpty();
        assertThat(amountsOf(tenant, b)).isEmpty();
        assertThat(myPay(first)).doesNotContain("8989");
        assertThat(myPay(second)).doesNotContain("8989");
        assertThat(unmatched(tenant)).singleElement().satisfies(u -> assertThat(u)
                .containsEntry("reason", "ambiguous employee").containsEntry("candidates", 2));
    }

    @Test
    void r1_5_1_anAmbiguousAddressWithAUniqueNamePaysNobodyAndTheNamedOneSeesNothing() throws Exception {
        String mail = "amb-named-" + run + "@example.com";
        String named = employeeWithLogin("Сотрудник Уникальное ФИО", mail);
        employee(tenant, "Сотрудник Двойник Адреса", "AMB-NAMED-" + run + "@example.com");
        MockHttpServletResponse r = batch(tenant, connectorPayment("  Amb-Named-" + run + "@Example.com ",
                "Сотрудник Уникальное ФИО", "zup-amb", "9191"));
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("ambiguous employee");
        assertThat(amountsOf(tenant, named)).isEmpty();
        assertThat(myPay(mail)).doesNotContain("9191");
    }

    @Test
    void r1_5_1_aNameOfAnotherCompanyPaysNobodyHere() throws Exception {
        UUID other = UUID.randomUUID();
        String theirs = employee(other, "Сотрудник Только В Другой", "other-name-" + run + "@example.com");
        MockHttpServletResponse r = batch(tenant, connectorPayment(null, "Сотрудник Только В Другой", "zup-other",
                "4242"));
        assertThat(JsonPath.<List<String>>read(body(r), "$.skipped[*].reason")).containsExactly("employee not found");
        assertThat(amountsOf(other, theirs)).isEmpty();
        assertThat(unmatched(other)).isEmpty();
    }

    @Test
    void r1_5_1_theSameUnmatchedPaymentAgainAndInParallelIsOneRowAndPaysNobody() throws Exception {
        String mail = "rep-" + run + "@example.com";
        String named = employeeWithLogin("Сотрудник Повтор", mail);
        String payment = connectorPayment("rep-typo-" + run + "@example.com", "Сотрудник Повтор", "zup-rep", "6363");
        batch(tenant, payment);
        batch(tenant, payment);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> batch(tenant, payment).getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unmatched(tenant)).hasSize(1);
        assertThat(amountsOf(tenant, named)).isEmpty();
        assertThat(myPay(mail)).doesNotContain("6363");
    }

    // races, and who may see the kept rows

    @Test
    void parallelImportsStoreOnePaymentAndKeepOneRow() throws Exception {
        String good = employee(tenant, "Сотрудник Параллель", "par-" + run + "@example.com");
        employee(tenant, "Сотрудник Неясный 1", "amb-" + run + "@example.com");
        employee(tenant, "Сотрудник Неясный 2", "Amb-" + run + "@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> imports = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                imports.add(() -> sheet(tenant, "par-" + run + "@example.com;Аванс;2026-10;24.10.2026;250,00",
                        "amb-" + run + "@example.com;Аванс;2026-10;24.10.2026;260,00").getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(imports)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(amountsOf(tenant, good)).containsExactly(250.0);
        assertThat(payments(tenant)).hasSize(1);
        assertThat(unmatched(tenant)).hasSize(1);
    }

    @Test
    void onlyOwnerAndFinanceSeeTheKeptRows() throws Exception {
        employee(tenant, "Сотрудник Скрытый 1", "hid-" + run + "@example.com");
        employee(tenant, "Сотрудник Скрытый 2", "Hid-" + run + "@example.com");
        sheet(tenant, "hid-" + run + "@example.com;Аванс;2026-10;24.10.2026;10,00");
        for (RequestPostProcessor allowed : List.of(owner(tenant), finance(tenant))) {
            MockHttpServletResponse r = call(get("/api/v1/payroll/unmatched"), allowed);
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(body(r), "$[*].reportedEmail"))
                    .containsExactly("hid-" + run + "@example.com");
        }
        for (String role : List.of("it_admin", "employee", "connector", "auditor", "agent_owner")) {
            MockHttpServletResponse r = call(get("/api/v1/payroll/unmatched"),
                    as(tenant, role + "-" + run + "@payroll.example", role));
            assertThat(r.getStatus()).as(role).isEqualTo(403);
            assertThat(body(r)).as(role).doesNotContain("hid-" + run);
        }
    }
}
