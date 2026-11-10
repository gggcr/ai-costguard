package ai.costguard.core.analytics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The forecast job publishes, people read the newest (ADR 0034). */
@SpringBootTest
@AutoConfigureMockMvc
class ForecastApiIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    static String series(String department, String name, String model, String... values) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            points.append(i == 0 ? "" : ",").append("""
                    {"month":"2026-1%d","value":%s,"low":0,"high":%s,"baseline":null}"""
                    .formatted(i + 1, values[i], values[i] + "0"));
        }
        return """
                {"departmentId":%s,"name":"%s","model":"%s","wape":0.05,"mase":0.4,"challenger":"naive",
                 "challengerWape":0.09,"history":[{"month":"2026-10","amount":100}],"points":[%s]}"""
                .formatted(department == null ? "null" : "\"" + department + "\"", name, model, points);
    }

    static String document(String company, String... departments) {
        return """
                {"currency":"RUB","horizon":2,"generatedBy":"test","company":%s,"departments":[%s],
                 "savings":[{"month":"2026-11","expected":800,"confirmed":0},{"month":"2026-12","expected":800,
                 "confirmed":300}]}""".formatted(company, String.join(",", departments));
    }

    @Test
    void theJobPublishesAndPeopleReadTheNewest() throws Exception {
        mvc.perform(get("/api/v1/analytics/forecast").with(as("owner"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("forecast_not_found"));

        String it = UUID.randomUUID().toString();
        String first = document(series(null, "Компания", "holt", "150.00", "160.00"),
                series(it, "ИТ", "theta", "100.00", "105.00"), series(null, "Без отдела", "naive", "50.00", "55.00"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(first)
                .with(as("forecast"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty());
        String second = document(series(null, "Компания", "ets_damped", "170.00", "180.00"),
                series(it, "ИТ", "theta", "120.00", "125.00"), series(null, "Без отдела", "naive", "50.00", "55.00"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(second)
                .with(as("finance"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/analytics/forecast").with(as("auditor"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.forecast.company.model").value("ets_damped"))
                .andExpect(jsonPath("$.forecast.company.points[1].value").value(180.0))
                .andExpect(jsonPath("$.forecast.departments[0].departmentId").value(it))
                .andExpect(jsonPath("$.forecast.departments[0].challengerWape").value(0.09))
                .andExpect(jsonPath("$.forecast.savings[1].confirmed").value(300))
                .andExpect(jsonPath("$.forecast.agents").doesNotExist());

        // with the agents of the company (ADR 0045)
        String agent = UUID.randomUUID().toString();
        String third = second.substring(0, second.lastIndexOf('}')) + """
                ,"agents":[{"agentId":"%s","model":"gbm","wape":0.11,"challenger":"weekly","challengerWape":0.12,
                 "total":3000,"low":2500,"high":3500,"days":[{"day":"2026-11-10","value":100}]}]}""".formatted(agent);
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(third)
                .with(as("forecast"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/forecast").with(as("owner")))
                .andExpect(jsonPath("$.forecast.agents[0].agentId").value(agent))
                .andExpect(jsonPath("$.forecast.agents[0].model").value("gbm"))
                .andExpect(jsonPath("$.forecast.agents[0].days[0].day").value("2026-11-10"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON)
                .content(third.replace("2026-11-10", "10.11.2026")).with(as("forecast")))
                .andExpect(status().isBadRequest());

        // another company sees nothing
        mvc.perform(get("/api/v1/analytics/forecast").with(jwt().jwt(j -> j.claim("tenant_id",
                        UUID.randomUUID().toString()).claim("realm_access", Map.of("roles", List.of("owner"))))
                .authorities(realmRoles))).andExpect(status().isNotFound());
    }

    @Test
    void aForecastThatDoesNotAddUpOrComesFromAnyoneElseIsRefused() throws Exception {
        String broken = document(series(null, "Компания", "holt", "150.00", "160.00"),
                series(UUID.randomUUID().toString(), "ИТ", "theta", "100.00", "100.00"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(broken)
                .with(as("forecast"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("forecast_does_not_add_up"));
        String short_ = document(series(null, "Компания", "holt", "150.00"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(short_)
                .with(as("forecast"))).andExpect(status().isBadRequest());
        String fine = document(series(null, "Компания", "holt", "150.00", "160.00"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON).content(fine)
                .with(as("auditor"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("role_required"));
        mvc.perform(put("/api/v1/analytics/forecast").contentType(MediaType.APPLICATION_JSON)
                .content(fine.replace("\"RUB\"", "\"rub\"")).with(as("forecast"))).andExpect(status().isBadRequest());
    }
}
