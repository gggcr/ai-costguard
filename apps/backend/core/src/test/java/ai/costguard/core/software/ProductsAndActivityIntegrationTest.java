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

/** PRODUCT and ACTIVITY_EVENT of the data model (ADR 0042). */
@SpringBootTest
@AutoConfigureMockMvc
class ProductsAndActivityIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired NormalizedEntities entities;
    @Autowired Audit audit;

    final UUID tenant = UUID.randomUUID();
    // the date of the audit, whose clock is UTC (AssetsConfig)
    final LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

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

    String subscription(String vendor, String department, String product) throws Exception {
        return send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":2,"pricePerSeat":500,
                 "billingPeriod":"month","renewalDate":"2027-01-01"}""".formatted(vendor, product, department));
    }

    String employee(String department, String email) throws Exception {
        return send(post("/api/v1/employees"), """
                {"name":"Сотрудник","email":"%s","departmentId":"%s","position":"Dev"}""".formatted(email, department));
    }

    @Test
    void aSignInOfAPersonMovesTheirLicenseAndStaysAsAnEvent() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"Notion\",\"inn\":\"\",\"category\":\"\"}");
        String notion = subscription(vendor, it, "Notion  Plus");
        String email = "ann-" + UUID.randomUUID() + "@corp.ru";
        String ann = employee(it, email);
        String license = send(post("/api/v1/subscriptions/" + notion + "/licenses"), "{\"employeeId\":\"%s\"}"
                .formatted(ann));

        // the subscription got its product, under one spelling of the name
        List<Map<String, Object>> products = JsonPath.read(json("/api/v1/products"), "$[*]");
        assertThat(products).singleElement().satisfies(p -> {
            assertThat(p.get("name")).isEqualTo("Notion  Plus");
            assertThat(p.get("subscriptions")).isEqualTo(1);
        });
        assertThat(JsonPath.<String>read(json("/api/v1/subscriptions/" + notion), "$.productId"))
                .isEqualTo(products.getFirst().get("id"));

        Instant at = Instant.parse(today.minusDays(2) + "T09:15:00Z");
        String payload = """
                {"vendor":"Notion","product":"notion plus","email":"%s","at":"%s","kind":"sign_in","source":"okta"}"""
                .formatted(email, at);
        entities.received(tenant, UUID.randomUUID(), tenant + ":activity:1", "ACTIVITY_EVENT", payload, 1.0,
                Instant.now());
        entities.received(tenant, UUID.randomUUID(), tenant + ":activity:1", "ACTIVITY_EVENT", payload, 1.0,
                Instant.now()); // again: kept once

        List<Map<String, Object>> events = JsonPath.read(json("/api/v1/subscriptions/" + notion + "/activity"), "$[*]");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.get("employeeId")).isEqualTo(ann);
            assertThat(e.get("source")).isEqualTo("okta");
        });
        List<Map<String, Object>> licenses = JsonPath.read(json("/api/v1/subscriptions/" + notion + "/licenses"),
                "$[*]");
        assertThat(licenses).filteredOn(l -> l.get("id").equals(license)).singleElement()
                .satisfies(l -> assertThat(l.get("lastActive")).isEqualTo(today.minusDays(2).toString()));

        // a last activity a person enters is an event of that day too
        send(put("/api/v1/licenses/" + license + "/activity"), "{\"lastActive\":\"%s\"}".formatted(today));
        assertThat(JsonPath.<List<String>>read(json("/api/v1/subscriptions/" + notion + "/activity"), "$[*].source"))
                .containsExactly("manual", "okta");
    }

    @Test
    void theCategoryAPersonSetsIsWhatTheOverlapRuleReads() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String one = send(post("/api/v1/vendors"), "{\"name\":\"ООО Альфа\",\"inn\":\"\",\"category\":\"\"}");
        String two = send(post("/api/v1/vendors"), "{\"name\":\"ООО Бета\",\"inn\":\"\",\"category\":\"\"}");
        String a = subscription(one, it, "Корпоративный мессенджер");
        String b = subscription(two, it, "Рабочий чат Бета");
        for (String s : List.of(a, b)) {
            String who = employee(it, "p-" + UUID.randomUUID() + "@corp.ru");
            send(post("/api/v1/subscriptions/" + a + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(who));
            send(post("/api/v1/subscriptions/" + b + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(who));
        }
        audit.run(tenant);
        assertThat(rules()).doesNotContain("subscription.overlap"); // the dictionary knows neither name

        List<String> ids = JsonPath.read(json("/api/v1/products"), "$[*].id");
        for (String id : ids) {
            mvc.perform(put("/api/v1/products/" + id + "/category").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"category\":\" Chat \"}").with(owner())).andExpect(status().isOk());
        }
        assertThat(JsonPath.<List<String>>read(json("/api/v1/products"), "$[*].category")).containsOnly("chat");
        audit.run(tenant);
        assertThat(rules()).contains("subscription.overlap");

        mvc.perform(put("/api/v1/products/" + UUID.randomUUID() + "/category").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"chat\"}").with(owner())).andExpect(status().isNotFound());
    }

    List<String> rules() throws Exception {
        return JsonPath.read(json("/api/v1/findings"), "$[?(@.status == 'new')].rule");
    }
}
