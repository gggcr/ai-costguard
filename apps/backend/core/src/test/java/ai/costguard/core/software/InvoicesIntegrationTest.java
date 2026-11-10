package ai.costguard.core.software;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.findings.Audit;
import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Invoices and payments in the core, and the saving of a finding confirmed by the next invoice (ADR 0033). */
@SpringBootTest
@AutoConfigureMockMvc
class InvoicesIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Audit audit;
    @Autowired NormalizedEntities entities;

    final UUID tenant = UUID.randomUUID();
    final LocalDate today = LocalDate.now();

    RequestPostProcessor owner() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of("owner")))).authorities(realmRoles);
    }

    String send(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(owner()))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : JsonPath.read(response, "$.id");
    }

    String json(String url) throws Exception {
        return mvc.perform(get(url).with(owner())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    void entity(String key, String type, String payload) {
        entities.received(tenant, UUID.randomUUID(), tenant + ":" + key, type, payload, 1.0, Instant.now());
    }

    void invoice(String key, String vendor, String number, LocalDate day, int total) {
        entity(key, "INVOICE", """
                {"vendor":"%s","number":"%s","total":"%d","currency":"RUB","date":"%s","source":"mail",
                 "lines":[{"description":"Подписка, %d ₽","amount":"%d"}]}"""
                .formatted(vendor, number, total, day, total, total));
    }

    /** A subscription with an unused license, its finding taken up to "in progress". */
    String findingInProgress(String department, String vendorName, String product, int seats, int price)
            throws Exception {
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"%s\",\"inn\":\"\",\"category\":\"\"}"
                .formatted(vendorName));
        String subscription = send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":%d,
                 "pricePerSeat":%d,"billingPeriod":"month","renewalDate":"2027-01-01"}"""
                .formatted(vendor, product, department, seats, price));
        for (int i = 0; i < seats; i++) {
            String employee = send(post("/api/v1/employees"), """
                    {"name":"Сотрудник %s %d","email":"e%d-%s@corp.ru","departmentId":"%s","position":"Dev"}"""
                    .formatted(product, i, i, UUID.randomUUID(), department));
            String license = send(post("/api/v1/subscriptions/" + subscription + "/licenses"),
                    "{\"employeeId\":\"%s\"}".formatted(employee));
            send(put("/api/v1/licenses/" + license + "/activity"), "{\"lastActive\":\"%s\"}"
                    .formatted(i == 0 ? today.minusDays(60) : today));
        }
        audit.run(tenant);
        List<String> ids = JsonPath.read(json("/api/v1/findings"),
                "$[?(@.rule == 'license.unused' && @.subscriptionId == '" + subscription + "')].id");
        String id = ids.getFirst();
        act(id, "{\"action\":\"assign\",\"ownerId\":\"%s\"}".formatted(UUID.randomUUID()));
        act(id, "{\"action\":\"accept\"}");
        act(id, "{\"action\":\"createTask\",\"reason\":\"отозвать лицензию\"}");
        return id;
    }

    void act(String findingId, String body) throws Exception {
        mvc.perform(post("/api/v1/findings/" + findingId + "/actions").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(owner())).andExpect(status().isOk());
    }

    Map<String, Object> finding(String id) throws Exception {
        return JsonPath.read(json("/api/v1/findings/" + id), "$");
    }

    @Test
    void invoicesArePaidAndTiedToTheirSubscription() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"Атлассиан\",\"inn\":\"\",\"category\":\"\"}");
        String jira = send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"Jira","contractId":null,"departmentId":"%s","seats":3,"pricePerSeat":1000,
                 "billingPeriod":"month","renewalDate":"2027-01-01"}""".formatted(vendor, it));
        send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"Confluence","contractId":null,"departmentId":"%s","seats":3,
                 "pricePerSeat":500,"billingPeriod":"month","renewalDate":"2027-01-01"}""".formatted(vendor, it));

        // two subscriptions of the vendor: the line names the product
        entity("invoice:mail:a-1", "INVOICE", """
                {"vendor":"ООО «Атлассиан»","number":"A-1","total":"3000","currency":"RUB","date":"%s",
                 "source":"mail","lines":[{"description":"Jira Software Cloud, 3 места","amount":"3000"}]}"""
                .formatted(today.minusDays(40)));
        entity("invoice:mail:a-2", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-2","total":"4500","currency":"RUB","date":"%s","source":"mail",
                 "lines":[{"description":"Jira","amount":"3000"},{"description":"Confluence","amount":"1500"}]}"""
                .formatted(today.minusDays(10)));
        entity("payment:bank:1", "PAYMENT", """
                {"vendor":"Атлассиан","amount":"3000.00","date":"%s","kind":"TRANSFER","purpose":"Оплата по счёту A-1"}"""
                .formatted(today.minusDays(38)));
        entity("payment:bank:2", "PAYMENT", """
                {"vendor":"Атлассиан","amount":"777","date":"%s","kind":"TRANSFER"}""".formatted(today.minusDays(5)));

        String invoices = json("/api/v1/invoices?vendorId=" + vendor);
        List<Map<String, Object>> list = JsonPath.read(invoices, "$[*]");
        assertThat(list).extracting(i -> i.get("number")).containsExactly("A-2", "A-1");
        Map<String, Object> a1 = list.get(1);
        assertThat(a1.get("subscriptionId")).isEqualTo(jira);
        assertThat(a1.get("paymentId")).isNotNull();
        assertThat(JsonPath.<List<String>>read(invoices, "$[1].lines[*].description"))
                .containsExactly("Jira Software Cloud, 3 места");
        // both products are named: nothing says which subscription the second invoice is for; nobody paid it
        assertThat(list.get(0).get("subscriptionId")).isNull();
        assertThat(list.get(0).get("paymentId")).isNull();

        List<Map<String, Object>> payments = JsonPath.read(json("/api/v1/payments"), "$[*]");
        assertThat(payments).hasSize(2);
        assertThat(payments).filteredOn(p -> p.get("amount").toString().equals("3000.0"))
                .extracting(p -> p.get("invoiceId")).containsExactly(a1.get("id"));
    }

    @Test
    void theNextInvoiceConfirmsTheSaving() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String id = findingInProgress(it, "Атлассиан", "Jira", 3, 1000);
        assertThat(finding(id).get("expectedSavings").toString()).isEqualTo("1000.0");

        invoice("invoice:mail:j-1", "Атлассиан", "J-1", today.minusDays(30), 3000); // before the task
        assertThat(finding(id).get("status")).isEqualTo("in_progress");
        invoice("invoice:mail:j-2", "Атлассиан", "J-2", today, 2000); // the license is gone

        Map<String, Object> f = finding(id);
        assertThat(f.get("status")).isEqualTo("confirmed");
        assertThat(JsonPath.<List<String>>read(json("/api/v1/findings/" + id), "$.history[*].text"))
                .anyMatch(t -> t.startsWith("Экономия видна в счёте № J-2") && t.contains("было 3000.00 ₽"));
    }

    @Test
    void twoInvoicesWithoutTheSavingSendTheFindingBackToReview() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String id = findingInProgress(it, "Zoom", "Zoom Pro", 2, 500);

        invoice("invoice:mail:z-1", "Zoom", "Z-1", today.minusDays(30), 1000);
        invoice("invoice:mail:z-2", "Zoom", "Z-2", today, 1000);
        assertThat(finding(id).get("status")).isEqualTo("in_progress");
        assertThat(finding(id).get("missedInvoices")).isEqualTo(1);

        // the same invoice from 1C is a copy, not a second month
        invoice("invoice:1c:z-2", "Zoom", "Z-2", today, 1000);
        assertThat(finding(id).get("missedInvoices")).isEqualTo(1);

        invoice("invoice:mail:z-3", "Zoom", "Z-3", today, 1000);
        assertThat(finding(id).get("status")).isEqualTo("review");
    }

    @Test
    void withoutAnInvoiceBeforeTheTaskThereIsNothingToCompare() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String id = findingInProgress(it, "Miro", "Miro Business", 2, 800);

        invoice("invoice:mail:m-1", "Miro", "M-1", today, 800);

        assertThat(finding(id).get("status")).isEqualTo("in_progress");
        assertThat(finding(id).get("missedInvoices")).isEqualTo(0);
        assertThat(JsonPath.<List<String>>read(json("/api/v1/findings/" + id), "$.history[*].text"))
                .anyMatch(t -> t.contains("сравнить не с чем"));
    }
}
