package ai.costguard.core.software;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class SoftwareApiIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "u@x.ru")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestPostProcessor finance() {
        return as(tenant, "finance");
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post(path), body).with(finance())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String vendor() throws Exception {
        return create("/api/v1/vendors", "{\"name\":\"%s\",\"inn\":\"7700\",\"category\":\"SaaS\"}"
                .formatted(UUID.randomUUID()));
    }

    private static String contract(String vendor, String number, String start, String end) {
        return """
                {"vendorId":"%s","number":"%s","startDate":"%s","endDate":"%s","noticeDays":30,"autoRenew":true,
                 "amount":120000}
                """.formatted(vendor, number, start, end);
    }

    private static String subscription(String vendor, String contract, int seats) {
        return """
                {"vendorId":"%s","product":"Jira","contractId":%s,"departmentId":"%s","seats":%d,
                 "pricePerSeat":790,"billingPeriod":"month","renewalDate":"2027-01-01"}
                """.formatted(vendor, contract == null ? "null" : '"' + contract + '"', UUID.randomUUID(), seats);
    }

    @Test
    void seatsCannotBeOverbooked() throws Exception {
        String sub = create("/api/v1/subscriptions", subscription(vendor(), null, 1));
        String first = "{\"employeeId\":\"%s\"}".formatted(UUID.randomUUID());

        mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"), first).with(finance()))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"), first).with(finance()))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"),
                        "{\"employeeId\":\"%s\"}".formatted(UUID.randomUUID())).with(finance()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("seats_exhausted"));
        mvc.perform(get("/api/v1/subscriptions/" + sub).with(finance())).andExpect(jsonPath("$.assigned").value(1));
    }

    @Test
    void seatsCannotDropBelowAssignedLicenses() throws Exception {
        String vendor = vendor();
        String sub = create("/api/v1/subscriptions", subscription(vendor, null, 2));
        mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"),
                "{\"employeeId\":\"%s\"}".formatted(UUID.randomUUID())).with(finance()));

        mvc.perform(json(put("/api/v1/subscriptions/" + sub), subscription(vendor, null, 0)).with(finance()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("seats_in_use"));
    }

    @Test
    void aSubscriptionTakesOnlyItsVendorsContract() throws Exception {
        String other = create("/api/v1/contracts", contract(vendor(), "C-1", "2026-01-01", "2026-12-31"));

        mvc.perform(json(post("/api/v1/subscriptions"), subscription(vendor(), other, 5)).with(finance()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("contract_of_another_vendor"));
    }

    @Test
    void contractDatesAreChecked() throws Exception {
        mvc.perform(json(post("/api/v1/contracts"), contract(vendor(), "C-2", "2026-12-31", "2026-01-01"))
                        .with(finance()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("dates_reversed"));
    }

    @Test
    void activityOnlyMovesForward() throws Exception {
        String sub = create("/api/v1/subscriptions", subscription(vendor(), null, 3));
        String license = JsonPath.read(mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"),
                "{\"employeeId\":\"%s\"}".formatted(UUID.randomUUID())).with(finance()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(json(put("/api/v1/licenses/" + license + "/activity"), "{\"lastActive\":\"2026-11-05\"}")
                .with(finance())).andExpect(jsonPath("$.lastActive").value("2026-11-05"));
        mvc.perform(json(put("/api/v1/licenses/" + license + "/activity"), "{\"lastActive\":\"2026-10-01\"}")
                .with(finance())).andExpect(jsonPath("$.lastActive").value("2026-11-05"));
    }

    @Test
    void theDismissalChecklistListsLicenses() throws Exception {
        RequestPostProcessor admin = as(tenant, "it_admin");
        String department = JsonPath.read(mvc.perform(json(post("/api/v1/departments"),
                "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String employee = JsonPath.read(mvc.perform(json(post("/api/v1/employees"), """
                {"name":"A","email":"%s@x.ru","departmentId":"%s","position":"P"}
                """.formatted(UUID.randomUUID(), department)).with(admin)).andReturn().getResponse().getContentAsString(),
                "$.id");
        String sub = create("/api/v1/subscriptions", subscription(vendor(), null, 3));
        mvc.perform(json(post("/api/v1/subscriptions/" + sub + "/licenses"), "{\"employeeId\":\"%s\"}"
                .formatted(employee)).with(finance())).andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees/" + employee + "/dismissal-checklist").with(admin))
                .andExpect(jsonPath("$.sections[?(@.name == 'licenses')].items[0].cost").value(790.0))
                .andExpect(jsonPath("$.sections[?(@.name == 'licenses')].totalCost").value(790.0));
    }

    @Test
    void tenantsAreIsolatedAndAuditorsOnlyRead() throws Exception {
        String vendor = vendor();
        UUID other = UUID.randomUUID();

        mvc.perform(get("/api/v1/vendors").with(as(other, "owner"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(json(post("/api/v1/subscriptions"), subscription(vendor, null, 1)).with(as(other, "owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("vendor_not_found"));
        mvc.perform(json(post("/api/v1/vendors"), "{\"name\":\"x\",\"inn\":\"\",\"category\":\"\"}")
                .with(as(tenant, "auditor"))).andExpect(status().isForbidden());
    }
}
