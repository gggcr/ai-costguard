package ai.costguard.core.findings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The six rules of ADR 0032: overlapping tools, oversized plan, monthly billing, invoice errors, shadow SaaS, AI. */
@SpringBootTest
@AutoConfigureMockMvc
class AuditRulesIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Audit audit;
    @Autowired NormalizedEntities entities;
    @Autowired JdbcClient jdbc;

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

    List<Map<String, Object>> findings(String rule) throws Exception {
        List<Map<String, Object>> all = JsonPath.read(mvc.perform(get("/api/v1/findings").with(owner()))
                .andReturn().getResponse().getContentAsString(), "$[*]");
        return all.stream().filter(f -> rule.equals(f.get("rule"))).toList();
    }

    List<String> statuses(String rule) throws Exception {
        return findings(rule).stream().map(f -> (String) f.get("status")).toList();
    }

    String saving(String rule) throws Exception {
        List<Map<String, Object>> found = findings(rule);
        assertThat(found).hasSize(1);
        return found.getFirst().get("expectedSavings").toString();
    }

    String subscription(String vendor, String product, String department, int seats, int price, String plan)
            throws Exception {
        return send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":%d,"pricePerSeat":%d,
                 "billingPeriod":"month","renewalDate":"2027-01-01","plan":%s}"""
                .formatted(vendor, product, department, seats, price, plan == null ? "null" : "\"" + plan + "\""));
    }

    @Test
    void softwareRules() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String alice = send(post("/api/v1/employees"), """
                {"name":"Алиса","email":"alice@corp.ru","departmentId":"%s","position":"Dev"}""".formatted(it));
        String bob = send(post("/api/v1/employees"), """
                {"name":"Боб","email":"bob@corp.ru","departmentId":"%s","position":"Dev"}""".formatted(it));
        String microsoft = send(post("/api/v1/vendors"), "{\"name\":\"Microsoft\",\"inn\":\"\",\"category\":\"\"}");
        String yandex = send(post("/api/v1/vendors"), "{\"name\":\"Яндекс\",\"inn\":\"\",\"category\":\"\"}");
        String bitrix = send(post("/api/v1/vendors"), "{\"name\":\"Битрикс24\",\"inn\":\"\",\"category\":\"\"}");

        String m365 = subscription(microsoft, "Microsoft 365", it, 2, 500, null);
        String y360 = subscription(yandex, "Яндекс 360", it, 2, 300, null);
        String b24 = subscription(bitrix, "Битрикс24", it, 1, 5990, "Стандартный");
        send(post("/api/v1/subscriptions/" + m365 + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(alice));
        send(post("/api/v1/subscriptions/" + m365 + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(bob));
        send(post("/api/v1/subscriptions/" + y360 + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(alice));
        String bobYandex = send(post("/api/v1/subscriptions/" + y360 + "/licenses"),
                "{\"employeeId\":\"%s\"}".formatted(bob));
        send(post("/api/v1/subscriptions/" + b24 + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(alice));

        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).with(owner()).content("""
                {"vendorId":"%s","product":"Битрикс24","name":"Пустой","perSeat":false,"maxUsers":5}"""
                .formatted(bitrix))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("plan_without_price"));
        send(post("/api/v1/plans"), """
                {"vendorId":"%s","product":"Битрикс24","name":"Стандартный","perSeat":false,"maxUsers":50,
                 "priceMonth":5990,"priceYear":59900}""".formatted(bitrix));
        send(post("/api/v1/plans"), """
                {"vendorId":"%s","product":"БИТРИКС24","name":"Базовый","perSeat":false,"maxUsers":5,
                 "priceMonth":1990,"priceYear":19900}""".formatted(bitrix));

        audit.run(tenant);

        // Microsoft 365 and Яндекс 360 are both mail and documents for the same two people: drop the cheaper seats
        assertThat(saving("subscription.overlap")).isEqualTo("600.0");
        assertThat(findings("subscription.overlap").getFirst().get("subscriptionId")).isEqualTo(y360);
        // one user on a plan for fifty, the plan for five is 4000 a month cheaper
        assertThat(saving("subscription.oversized-plan")).isEqualTo("4000.0");
        // the yearly price of the plan, else the usual discount of 17%
        assertThat(findings("subscription.monthly-billing")).extracting(f -> f.get("expectedSavings").toString())
                .containsExactlyInAnyOrder("170.0", "102.0", "998.33");

        // Bob leaves Яндекс 360: fewer people in both, and the subscription is no longer steady
        mvc.perform(delete("/api/v1/licenses/" + bobYandex).with(owner())).andExpect(status().isNoContent());
        // the company moves to the smaller plan
        mvc.perform(put("/api/v1/subscriptions/" + b24).contentType(MediaType.APPLICATION_JSON).with(owner())
                .content("""
                        {"vendorId":"%s","product":"Битрикс24","contractId":null,"departmentId":"%s","seats":1,
                         "pricePerSeat":1990,"billingPeriod":"month","renewalDate":"2027-01-01","plan":"Базовый"}"""
                        .formatted(bitrix, it)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("Базовый"));
        audit.run(tenant);

        assertThat(saving("subscription.overlap")).isEqualTo("300.0");
        assertThat(statuses("subscription.oversized-plan")).containsExactly("auto_closed");
        assertThat(findings("subscription.monthly-billing")).filteredOn(f -> f.get("status").equals("new"))
                .extracting(f -> f.get("expectedSavings").toString())
                .containsExactlyInAnyOrder("170.0", "331.67"); // 1990 a month against 19900 a year
    }

    void entity(String key, String type, String payload) {
        entities.received(tenant, UUID.randomUUID(), tenant + ":" + key, type, payload, 1.0, Instant.now());
    }

    @Test
    void invoiceAndPaymentRules() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String atlassian = send(post("/api/v1/vendors"), "{\"name\":\"Атлассиан\",\"inn\":\"\",\"category\":\"\"}");
        String jira = subscription(atlassian, "Jira", it, 3, 1000, null);
        String mandate = send(post("/api/v1/mandates"), """
                {"name":"M","models":["gpt-5"],"vendors":["OpenAI, Inc"],"maxPerOperation":500,
                 "approvalThreshold":300,"hours":"круглосуточно"}""");
        mvc.perform(put("/api/v1/mandates/{id}/publication", mandate).with(owner())).andExpect(status().isOk());
        LocalDate paid = today.minusDays(3);
        jdbc.sql("""
                insert into fx_rate (rate_date, currency, rate) values (?, 'USD', 100)
                on conflict (rate_date, currency) do update set rate = excluded.rate
                """).param(paid).update();

        String day = today.minusDays(10).toString();
        entity("invoice:mail:a-17", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-17","total":"3000","currency":"RUB","date":"%s","source":"mail"}"""
                .formatted(day));
        entity("invoice:1c:a-17", "INVOICE", """
                {"vendor":"ООО «Атлассиан»","number":"№ A-17","total":"3000","currency":"RUB","date":"%s",
                 "source":"1c"}""".formatted(day));
        entity("invoice:mail:a-18", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-18","total":"3600","currency":"RUB","date":"%s","source":"mail"}"""
                .formatted(day));
        entity("invoice:mail:a-19", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-19","total":"9000","currency":"RUB","date":"%s","source":"mail"}"""
                .formatted(day));
        entity("invoice:mail:old", "INVOICE", """
                {"vendor":"Атлассиан","number":"A-1","total":"99000","currency":"RUB","date":"%s","source":"mail"}"""
                .formatted(today.minusDays(200)));

        entity("payment:bank:1", "PAYMENT", """
                {"vendor":"Notion","amount":"1500","date":"%s","kind":"CARD","card":"4276****1234"}"""
                .formatted(paid));
        entity("payment:bank:2", "PAYMENT", """
                {"vendor":"ООО Ромашка-Сервис","amount":"80000","date":"%s","kind":"TRANSFER"}""".formatted(paid));
        entity("payment:bank:3", "PAYMENT", """
                {"vendor":"OpenAI","amount":"20","currency":"USD","date":"%s","kind":"CARD","card":"4276****1234",
                 "purpose":"CHATGPT SUBSCRIPTION"}""".formatted(paid));
        entity("payment:bank:4", "PAYMENT", """
                {"vendor":"OpenAI","amount":"10000","date":"%s","kind":"TRANSFER"}""".formatted(paid));
        entity("payment:bank:5", "PAYMENT", """
                {"vendor":"Anthropic","amount":"5000","date":"%s","kind":"TRANSFER"}""".formatted(paid));

        audit.run(tenant);

        // A-17 came from the mail and from 1C; the later copy is the finding, once
        assertThat(saving("invoice.duplicate")).isEqualTo("3000.0");
        // A-18 is 600 above three seats of 1000; A-19 pays three months ahead; the old invoice is out of the window
        assertThat(saving("invoice.overcharge")).isEqualTo("600.0");
        assertThat(findings("invoice.overcharge").getFirst().get("subscriptionId")).isEqualTo(jira);
        // Notion by card with no subscription; the other vendor is no software; OpenAI is the AI rule's
        assertThat(saving("vendor.shadow")).isEqualTo("500.0");
        assertThat(findings("vendor.shadow").getFirst().get("title").toString()).contains("Notion");
        // the card payment to OpenAI and the transfer to Anthropic, which no mandate names; not the gateway's own
        // transfer to OpenAI
        List<Map<String, Object>> bypass = findings("ai.bypass");
        assertThat(bypass).extracting(f -> f.get("title").toString())
                .containsExactlyInAnyOrder("ИИ-расходы мимо шлюза: OpenAI", "ИИ-расходы мимо шлюза: Anthropic");
        assertThat(bypass).filteredOn(f -> f.get("title").toString().endsWith("OpenAI"))
                .extracting(f -> f.get("description").toString())
                .singleElement().asString().contains("1 платёж").contains("2000.00 RUB").contains("оплата картой");

        // Notion gets into the registry: the shadow finding closes by itself
        List<String> notions = JsonPath.read(mvc.perform(get("/api/v1/vendors").with(owner())).andReturn()
                .getResponse().getContentAsString(), "$[?(@.name == 'Notion')].id");
        String notion = notions.getFirst();
        subscription(notion, "Notion Plus", it, 1, 1500, null);
        audit.run(tenant);
        assertThat(statuses("vendor.shadow")).containsExactly("auto_closed");
    }
}
