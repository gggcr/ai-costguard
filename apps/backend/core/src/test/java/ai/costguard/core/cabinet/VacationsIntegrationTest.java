package ai.costguard.core.cabinet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Отпуска (ADR 0053): the employee asks, the head of the department decides, the days add up. */
@SpringBootTest
@AutoConfigureMockMvc
class VacationsIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    // a day in the middle of next year: never in the past, and every test stays inside one calendar year
    private final LocalDate start = LocalDate.of(LocalDate.now().getYear() + 1, 3, 2);

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as("admin@corp.ru", "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String department() throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                        "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** An employee with the CostGuard account an onboarding or an invitation leaves, linked to their Keycloak sub. */
    private String employee(String department, String email) throws Exception {
        String id = employeeRecord(department, email);
        login(tenant, email, "employee", UUID.fromString(id));
        return id;
    }

    private String employeeRecord(String department, String email) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/employees"),
                        "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                                .formatted(email, email, department)).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String ask(String email, LocalDate from, LocalDate to) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/me/vacations"),
                        "{\"kind\":\"annual\",\"startsOn\":\"%s\",\"endsOn\":\"%s\",\"comment\":\"на море\"}"
                                .formatted(from, to)).with(as(email, "employee")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void head(String department, String employeeId) throws Exception {
        mvc.perform(json(put("/api/v1/departments/{id}/head", department),
                        "{\"employeeId\":\"%s\"}".formatted(employeeId)).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.headEmployeeId").value(employeeId));
    }

    private static String mail(String who) {
        return who + "-" + UUID.randomUUID() + "@corp.ru";
    }

    @Test
    void theHeadDecidesAndTheDaysAddUp() throws Exception {
        String dept = department();
        String boss = mail("boss");
        String worker = mail("worker");
        head(dept, employee(dept, boss));
        employee(dept, worker);

        String id = ask(worker, start, start.plusDays(13));
        mvc.perform(get("/api/v1/me/vacations").param("year", String.valueOf(start.getYear())).with(as(worker, "employee")))
                .andExpect(jsonPath("$.pending").value(14))
                .andExpect(jsonPath("$.left").value(14))
                .andExpect(jsonPath("$.hasHead").value(true));
        mvc.perform(get("/api/v1/me/notifications").with(as(boss, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.containsString("просит отпуск")));
        mvc.perform(get("/api/v1/me/team/vacations").with(as(boss, "employee")))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("pending"));

        mvc.perform(json(put("/api/v1/me/team/vacations/{id}/decision", id), "{\"approve\":true}")
                        .with(as(boss, "employee")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));
        mvc.perform(get("/api/v1/me/vacations").param("year", String.valueOf(start.getYear())).with(as(worker, "employee")))
                .andExpect(jsonPath("$.used").value(14))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.left").value(14));
        mvc.perform(get("/api/v1/me/notifications").with(as(worker, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Отпуск согласован")));
        mvc.perform(json(put("/api/v1/me/team/vacations/{id}/decision", id), "{\"approve\":false}")
                .with(as(boss, "employee"))).andExpect(status().isConflict());
    }

    /** ADR 0074: an English company's head and employee are told in English. */
    @Test
    void anEnglishCompanyIsToldInEnglish() throws Exception {
        mvc.perform(json(put("/api/v1/company"), "{\"name\":\"Acme Inc\",\"currency\":\"USD\",\"language\":\"en\"}")
                .with(admin())).andExpect(status().isOk());
        String dept = department();
        String boss = mail("boss");
        String worker = mail("worker");
        head(dept, employee(dept, boss));
        employee(dept, worker);
        String id = ask(worker, start, start.plusDays(1));
        mvc.perform(get("/api/v1/me/notifications").with(as(boss, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.containsString("asks for a vacation")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.endsWith("(2 days)")));
        mvc.perform(json(put("/api/v1/me/team/vacations/{id}/decision", id), "{\"approve\":true}")
                .with(as(boss, "employee"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/notifications").with(as(worker, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Vacation approved: ")));
    }

    @Test
    void onlyTheHeadOfTheDepartmentDecides() throws Exception {
        String dept = department();
        String boss = mail("boss");
        String worker = mail("worker");
        String colleague = mail("colleague");
        head(dept, employee(dept, boss));
        employee(dept, worker);
        employee(dept, colleague);
        String id = ask(worker, start, start.plusDays(2));
        mvc.perform(json(put("/api/v1/me/team/vacations/{id}/decision", id), "{\"approve\":true}")
                        .with(as(colleague, "employee")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_the_head"));
        mvc.perform(json(put("/api/v1/me/team/vacations/{id}/decision", id), "{\"approve\":true}")
                .with(as(worker, "employee"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vacations").with(as(worker, "employee"))).andExpect(status().isForbidden());
    }

    @Test
    void theRulesOfARequest() throws Exception {
        String dept = department();
        String worker = mail("worker");
        employee(dept, worker);
        ask(worker, start, start.plusDays(9));
        mvc.perform(json(post("/api/v1/me/vacations"), "{\"kind\":\"annual\",\"startsOn\":\"%s\",\"endsOn\":\"%s\"}"
                        .formatted(start.plusDays(5), start.plusDays(12))).with(as(worker, "employee")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("vacation_overlap"));
        mvc.perform(json(post("/api/v1/me/vacations"), "{\"kind\":\"annual\",\"startsOn\":\"%s\",\"endsOn\":\"%s\"}"
                        .formatted(start.plusDays(20), start.plusDays(39))).with(as(worker, "employee")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("vacation_days_exceeded"));
        mvc.perform(json(post("/api/v1/me/vacations"), "{\"kind\":\"unpaid\",\"startsOn\":\"%s\",\"endsOn\":\"%s\"}"
                        .formatted(LocalDate.now().minusDays(3), LocalDate.now().minusDays(1)))
                .with(as(worker, "employee"))).andExpect(status().isBadRequest());
    }

    @Test
    void withoutAHeadTheEmployerDecidesAndTheEmployeeMayCancel() throws Exception {
        String dept = department();
        String worker = mail("worker");
        employee(dept, worker);
        String first = ask(worker, start, start.plusDays(4));
        mvc.perform(get("/api/v1/me/vacations").param("year", String.valueOf(start.getYear())).with(as(worker, "employee")))
                .andExpect(jsonPath("$.hasHead").value(false));
        mvc.perform(get("/api/v1/vacations").param("status", "pending").with(admin()))
                .andExpect(jsonPath("$[*].id").value(Matchers.hasItem(first)));
        mvc.perform(json(put("/api/v1/vacations/{id}/decision", first), "{\"approve\":false,\"comment\":\"сдача\"}")
                .with(admin())).andExpect(jsonPath("$.status").value("rejected"));

        String second = ask(worker, start.plusDays(60), start.plusDays(61));
        mvc.perform(delete("/api/v1/me/vacations/{id}", second).with(as(worker, "employee")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/vacations").param("year", String.valueOf(start.getYear())).with(as(worker, "employee")))
                .andExpect(jsonPath("$.pending").value(0));
    }
}
