package ai.costguard.core.findings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
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

@SpringBootTest
@AutoConfigureMockMvc
class AuditIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Audit audit;

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

    /** rule -> status of its findings */
    List<Map<String, Object>> findings() throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/findings").with(owner())).andReturn().getResponse()
                .getContentAsString(), "$[*]");
    }

    List<String> statuses(String rule) throws Exception {
        return findings().stream().filter(f -> rule.equals(f.get("rule"))).map(f -> (String) f.get("status")).toList();
    }

    @Test
    void rulesOpenAndCloseFindings() throws Exception {
        String department = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}");
        String alice = send(post("/api/v1/employees"), """
                {"name":"Алиса","email":"alice@corp.ru","departmentId":"%s","position":"Dev"}""".formatted(department));
        String bob = send(post("/api/v1/employees"), """
                {"name":"Боб","email":"bob@corp.ru","departmentId":"%s","position":"Dev"}""".formatted(department));
        String vendor = send(post("/api/v1/vendors"), "{\"name\":\"Атлассиан\",\"inn\":\"\",\"category\":\"\"}");
        String jira = send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"Jira","contractId":null,"departmentId":"%s","seats":3,
                 "pricePerSeat":1200,"billingPeriod":"year","renewalDate":"2027-01-01"}""".formatted(vendor, department));
        send(post("/api/v1/subscriptions"), """
                {"vendorId":"%s","product":"JIRA","contractId":null,"departmentId":"%s","seats":1,
                 "pricePerSeat":100,"billingPeriod":"month","renewalDate":"2027-01-01"}""".formatted(vendor, department));
        String aliceSeat = send(post("/api/v1/subscriptions/" + jira + "/licenses"), "{\"employeeId\":\"%s\"}"
                .formatted(alice));
        send(post("/api/v1/subscriptions/" + jira + "/licenses"), "{\"employeeId\":\"%s\"}".formatted(bob));
        send(put("/api/v1/licenses/" + aliceSeat + "/activity"), "{\"lastActive\":\"%s\"}"
                .formatted(today.minusDays(45)));
        send(post("/api/v1/contracts"), """
                {"vendorId":"%s","number":"A-1","startDate":"%s","endDate":"%s","noticeDays":30,"autoRenew":true,
                 "amount":120000}""".formatted(vendor, today.minusYears(1), today.plusDays(40)));
        String laptop = send(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-%s","model":"ThinkPad T14","type":"LAPTOP","cost":80000.00}"""
                .formatted(UUID.randomUUID()));
        send(put("/api/v1/assets/" + laptop + "/assignment"), "{\"employeeId\":\"%s\",\"returnDue\":\"2030-01-01\"}"
                .formatted(bob));
        mvc.perform(put("/api/v1/employees/" + bob + "/dismissal").with(owner())).andExpect(status().isOk());

        assertThat(audit.run(tenant)).isPositive();
        assertThat(statuses("license.unused")).containsExactly("new");
        assertThat(statuses("license.dismissed")).containsExactly("new");
        assertThat(statuses("subscription.idle-seats")).hasSize(2); // Jira has one free seat, JIRA has one
        assertThat(statuses("subscription.duplicate")).containsExactly("new");
        assertThat(statuses("contract.renewal")).containsExactly("new");
        assertThat(statuses("asset.not-returned")).containsExactly("new");
        var unused = findings().stream().filter(f -> "license.unused".equals(f.get("rule"))).findFirst().orElseThrow();
        assertThat(unused.get("expectedSavings").toString()).isEqualTo("100.0"); // 1200 a year is 100 a month

        // the conditions go away: Alice is active again, the laptop is back
        send(put("/api/v1/licenses/" + aliceSeat + "/activity"), "{\"lastActive\":\"%s\"}".formatted(today));
        mvc.perform(delete("/api/v1/assets/" + laptop + "/assignment").with(owner())).andExpect(status().isNoContent());
        audit.run(tenant);
        assertThat(statuses("license.unused")).containsExactly("auto_closed");
        assertThat(statuses("asset.not-returned")).containsExactly("auto_closed");
        assertThat(statuses("license.dismissed")).containsExactly("new");

        // running again changes nothing
        int before = findings().size();
        audit.run(tenant);
        assertThat(findings()).hasSize(before);
    }
}
