package ai.costguard.core.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** What if: changes over the registry, alone or over the published forecast (ADR 0034). */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioApiIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();
    final YearMonth next = YearMonth.now(ZoneOffset.UTC).plusMonths(1);

    RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    String send(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(as("owner")))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : JsonPath.read(response, "$.id");
    }

    String subscription(String vendor, String department, String product, int seats, int price, String period,
            String renewal, int holders) throws Exception {
        String id = send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":%d,"pricePerSeat":%d,
                 "billingPeriod":"%s","renewalDate":"%s"}"""
                .formatted(vendor, product, department, seats, price, period, renewal));
        for (int i = 0; i < holders; i++) {
            String employee = send(post("/api/v1/employees"), """
                    {"name":"Сотрудник %d","email":"%s@corp.ru","departmentId":"%s","position":"Dev"}"""
                    .formatted(i, UUID.randomUUID(), department));
            send(post("/api/v1/subscriptions/" + id + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(employee));
        }
        return id;
    }

    ResultActions scenario(String body) throws Exception {
        return mvc.perform(post("/api/v1/analytics/scenarios").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(as("auditor")));
    }

    @Test
    void changesCountFromTheirMonthAndBuildOnEachOther() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"Поставщик\",\"inn\":\"\",\"category\":\"\"}");
        String a = subscription(vendor, it, "A", 5, 100, "month", next.atDay(1).toString(), 3);
        subscription(vendor, it, "B", 2, 1200, "year", "2027-03-01", 2); // 200 a month
        String c = subscription(vendor, it, "C", 1, 300, "month", "2027-02-01", 1);

        String body = scenario("""
                {"horizon":3,"changes":[{"kind":"freeSeats"},
                 {"kind":"yearly","subscriptionId":"%s","discount":0.2,"from":"%s"}]}""".formatted(a, next.plusMonths(1)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.months[*].month"))
                .containsExactly(next.toString(), next.plusMonths(1).toString(), next.plusMonths(2).toString());
        assertThat(JsonPath.<List<Double>>read(body, "$.months[*].registry")).containsExactly(1000.0, 1000.0, 1000.0);
        // free seats: A pays for 3 of 5; then a year of 3 seats at 960 is 240 a month instead of 300
        assertThat(JsonPath.<List<Double>>read(body, "$.months[*].registryAfter")).containsExactly(800.0, 740.0, 740.0);
        assertThat(JsonPath.<Double>read(body, "$.monthlySavings")).isEqualTo(260.0);
        assertThat(JsonPath.<Double>read(body, "$.totalSavings")).isEqualTo(720.0);
        assertThat(JsonPath.<Boolean>read(body, "$.onForecast")).isFalse();
        // B and C have every seat taken: free seats change nothing there
        assertThat(JsonPath.<List<String>>read(body, "$.effects[*].kind")).containsExactly("freeSeats", "yearly");
        assertThat(JsonPath.<List<Double>>read(body, "$.effects[*].monthlySavings")).containsExactly(200.0, 60.0);

        scenario("{\"horizon\":2,\"changes\":[{\"kind\":\"cancel\",\"subscriptionId\":\"%s\"}]}".formatted(c))
                .andExpect(jsonPath("$.monthlySavings").value(300.0));
        scenario("{\"changes\":[{\"kind\":\"cancel\"}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("change_needs_subscription"));
        scenario("{\"changes\":[{\"kind\":\"seats\",\"subscriptionId\":\"%s\"}]}".formatted(a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("change_needs_value"));
        scenario("{\"changes\":[{\"kind\":\"cancel\",\"subscriptionId\":\"%s\"}]}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("scenario_subscription_not_found"));
    }

    @Test
    void overAPublishedForecastTheDifferenceComesOffIt() throws Exception {
        String it = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"Zoom\",\"inn\":\"\",\"category\":\"\"}");
        String zoom = subscription(vendor, it, "Zoom Pro", 4, 500, "month", "2027-01-01", 4);
        String company = ForecastApiIntegrationTest.series(null, "Компания", "holt", "5000.00", "5200.00");
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON)
                .content(ForecastApiIntegrationTest.document(company)).with(as("forecast"))).andExpect(status().isOk());

        scenario("""
                {"changes":[{"kind":"price","subscriptionId":"%s","pricePerSeat":350}]}""".formatted(zoom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onForecast").value(true))
                .andExpect(jsonPath("$.months.length()").value(2))
                .andExpect(jsonPath("$.months[0].month").value("2026-11"))
                .andExpect(jsonPath("$.months[0].forecast").value(5000.0))
                .andExpect(jsonPath("$.months[0].forecastAfter").value(4400.0))
                .andExpect(jsonPath("$.months[1].forecastAfter").value(4600.0))
                .andExpect(jsonPath("$.effects[0].subscription").value("Zoom Zoom Pro"));
    }
}
