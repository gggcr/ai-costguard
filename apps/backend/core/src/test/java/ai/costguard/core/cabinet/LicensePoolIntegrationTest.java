package ai.costguard.core.cabinet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

/** Пул лицензий (ADR 0055): free seats at once, idle licenses reassigned by IT, never lent. */
@SpringBootTest
@AutoConfigureMockMvc
class LicensePoolIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor it() {
        return as("it@corp.ru", "it_admin");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post(path), body).with(as("o@corp.ru", "owner")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String department;

    private String employee(String email) throws Exception {
        if (department == null) {
            department = create("/api/v1/departments", "{\"name\":\"%s\"}".formatted(UUID.randomUUID()));
        }
        return create("/api/v1/employees", "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, email, department));
    }

    private String subscription(String product, int seats) throws Exception {
        if (department == null) {
            department = create("/api/v1/departments", "{\"name\":\"%s\"}".formatted(UUID.randomUUID()));
        }
        String vendor = create("/api/v1/vendors", "{\"name\":\"%s\",\"inn\":\"\",\"category\":\"SaaS\"}"
                .formatted(UUID.randomUUID()));
        return create("/api/v1/subscriptions", """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":%d,
                 "pricePerSeat":1500,"billingPeriod":"month","renewalDate":"2027-01-01"}"""
                .formatted(vendor, product, department, seats));
    }

    private String give(String subscription, String employeeId, LocalDate lastActive) throws Exception {
        String license = JsonPath.read(mvc.perform(json(post("/api/v1/subscriptions/{id}/licenses", subscription),
                        "{\"employeeId\":\"%s\"}".formatted(employeeId)).with(it()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/licenses/{id}/activity", license),
                "{\"lastActive\":\"%s\"}".formatted(lastActive)).with(it())).andExpect(status().isOk());
        return license;
    }

    private static String mail(String who) {
        return who + "-" + UUID.randomUUID() + "@corp.ru";
    }

    @Test
    void aFreeSeatIsGivenAtOnce() throws Exception {
        String figma = subscription("Figma", 2);
        String anna = mail("anna");
        employee(anna);
        mvc.perform(get("/api/v1/me/licenses").with(as(anna, "employee")))
                .andExpect(jsonPath("$.pool[?(@.product == 'Figma')].free").value(Matchers.hasItem(2)));
        mvc.perform(json(post("/api/v1/me/license-requests"), "{\"subscriptionId\":\"%s\",\"reason\":\"макеты\"}"
                        .formatted(figma)).with(as(anna, "employee")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("granted"));
        mvc.perform(get("/api/v1/me/licenses").with(as(anna, "employee")))
                .andExpect(jsonPath("$.held[0].product").value("Figma"));
        mvc.perform(get("/api/v1/me/notifications").with(as(anna, "employee")))
                .andExpect(jsonPath("$.items[0].text").value("Вам выдана лицензия Figma из пула компании"));
        mvc.perform(json(post("/api/v1/me/license-requests"), "{\"subscriptionId\":\"%s\"}".formatted(figma))
                .with(as(anna, "employee"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("license_held"));
    }

    @Test
    void anIdleLicenseOfAColleagueGoesBackToThePoolWhenItApproves() throws Exception {
        String miro = subscription("Miro", 1);
        String idleOne = mail("idle");
        String worker = mail("worker");
        String idleId = employee(idleOne);
        String workerId = employee(worker);
        give(miro, idleId, LocalDate.now().minusDays(90));

        String request = JsonPath.read(mvc.perform(json(post("/api/v1/me/license-requests"),
                        "{\"subscriptionId\":\"%s\"}".formatted(miro)).with(as(worker, "employee")))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.takesIdle").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // the employee cannot decide; IT can
        mvc.perform(json(put("/api/v1/license-requests/{id}/decision", request), "{\"approve\":true}")
                .with(as(worker, "employee"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/license-requests/{id}/decision", request), "{\"approve\":true}").with(it()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));

        mvc.perform(get("/api/v1/me/licenses").with(as(worker, "employee")))
                .andExpect(jsonPath("$.held[0].product").value("Miro"));
        mvc.perform(get("/api/v1/me/licenses").with(as(idleOne, "employee")))
                .andExpect(jsonPath("$.held.length()").value(0));
        mvc.perform(get("/api/v1/me/notifications").with(as(idleOne, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Лицензия Miro возвращена в общий пул")));
        // the idle holder is not in the request the worker sees
        mvc.perform(get("/api/v1/me/licenses").with(as(worker, "employee")))
                .andExpect(jsonPath("$.requests[0].employeeName").value(worker));
        org.assertj.core.api.Assertions.assertThat(workerId).isNotBlank();
    }

    @Test
    void withoutAFreeOrIdleSeatItCannotApproveUntilSeatsAreAdded() throws Exception {
        String jira = subscription("Jira", 1);
        String busy = mail("busy");
        String worker = mail("worker");
        give(jira, employee(busy), LocalDate.now());
        employee(worker);
        String request = JsonPath.read(mvc.perform(json(post("/api/v1/me/license-requests"),
                        "{\"subscriptionId\":\"%s\"}".formatted(jira)).with(as(worker, "employee")))
                .andExpect(jsonPath("$.takesIdle").value(false))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/license-requests/{id}/decision", request), "{\"approve\":true}").with(it()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("no_free_seat"));
        mvc.perform(json(put("/api/v1/license-requests/{id}/decision", request),
                        "{\"approve\":false,\"comment\":\"лимит\"}").with(it()))
                .andExpect(jsonPath("$.status").value("rejected"));
    }

    @Test
    void idleLicensesGoBackToThePoolInOneGo() throws Exception {
        String zoom = subscription("Zoom", 3);
        give(zoom, employee(mail("a")), LocalDate.now().minusDays(45));
        give(zoom, employee(mail("b")), LocalDate.now().minusDays(60));
        give(zoom, employee(mail("c")), LocalDate.now().minusDays(2));
        mvc.perform(post("/api/v1/license-pool/reclaim").with(it()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.licenses").value(2));
        mvc.perform(get("/api/v1/license-pool").with(it()))
                .andExpect(jsonPath("$[?(@.product == 'Zoom')].assigned").value(Matchers.hasItem(1)));
    }
}
