package ai.costguard.core.cabinet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Запрос на покупку ПО (ADR 0056): a license the company has turns it down; otherwise finance decides. */
@SpringBootTest
@AutoConfigureMockMvc
class PurchasesIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private String department;

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.subject(subject(tenant, email)).claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor owner() {
        return as("o@corp.ru", "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(json(post(path), body).with(owner())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String dept() throws Exception {
        if (department == null) {
            department = create("/api/v1/departments", "{\"name\":\"%s\"}".formatted(UUID.randomUUID()));
        }
        return department;
    }

    /** An employee with the CostGuard account an onboarding or an invitation leaves, linked to their Keycloak sub. */
    private String employee(String email) throws Exception {
        String id = employeeRecord(email);
        login(tenant, email, "employee", UUID.fromString(id));
        return id;
    }

    private String employeeRecord(String email) throws Exception {
        return create("/api/v1/employees", "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, email, dept()));
    }

    private void subscription(String product, int seats) throws Exception {
        String vendor = create("/api/v1/vendors", "{\"name\":\"%s\",\"inn\":\"\",\"category\":\"SaaS\"}"
                .formatted(UUID.randomUUID()));
        create("/api/v1/subscriptions", ("{\"vendorId\":\"%s\",\"product\":\"%s\",\"contractId\":null,"
                + "\"departmentId\":\"%s\",\"seats\":%d,\"pricePerSeat\":1500,\"billingPeriod\":\"month\","
                + "\"renewalDate\":\"2027-01-01\"}").formatted(vendor, product, dept(), seats));
    }

    private static String mail(String who) {
        return who + "-" + UUID.randomUUID() + "@corp.ru";
    }

    @Test
    void aProgramTheCompanyHasIsNotBoughtButGivenFromThePool() throws Exception {
        subscription("Notion Plus", 3);
        String worker = mail("worker");
        employee(worker);
        var receipt = new MockMultipartFile("file", "чек.pdf", "application/pdf", "чек".getBytes(StandardCharsets.UTF_8));
        String id = JsonPath.read(mvc.perform(multipart("/api/v1/me/purchase-requests").file(receipt)
                        .param("product", "Notion").param("amount", "1200").param("period", "month")
                        .param("reason", "заметки").with(as(worker, "employee")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("has_license"))
                .andExpect(jsonPath("$.matchedProduct").value("Notion Plus"))
                .andExpect(jsonPath("$.licenseStatus").value("granted"))
                .andExpect(jsonPath("$.decisionComment").value(Matchers.containsString("выдана вам из пула")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/me/licenses").with(as(worker, "employee")))
                .andExpect(jsonPath("$.held[0].product").value("Notion Plus"));
        mvc.perform(get("/api/v1/me/notifications").with(as(worker, "employee")))
                .andExpect(jsonPath("$.items[*].text").value(Matchers.hasItem(
                        Matchers.startsWith("Отклонено: В компании есть свободная лицензия"))));
        mvc.perform(get("/api/v1/me/purchase-requests/{id}/file", id).with(as(worker, "employee")))
                .andExpect(status().isOk()).andExpect(content().bytes("чек".getBytes(StandardCharsets.UTF_8)));

        // the purchase not made is a saving, counted once (not again as the seat given)
        mvc.perform(get("/api/v1/cabinet/savings").with(as("f@corp.ru", "finance")))
                .andExpect(jsonPath("$.purchasesRefused").value(1))
                .andExpect(jsonPath("$.purchasesMonthly").value(1200.0))
                .andExpect(jsonPath("$.seatsFromPool").value(0))
                .andExpect(jsonPath("$.totalMonthly").value(1200.0))
                .andExpect(jsonPath("$.byMonth.length()").value(12));
        mvc.perform(get("/api/v1/cabinet/savings").with(as(worker, "employee"))).andExpect(status().isForbidden());
    }

    @Test
    void somethingNewGoesToFinanceAndTheManagerHearsTheAnswer() throws Exception {
        String head = mail("head");
        String worker = mail("worker");
        String headId = employee(head);
        employee(worker);
        mvc.perform(json(put("/api/v1/departments/{id}/head", dept()), "{\"employeeId\":\"%s\"}".formatted(headId))
                .with(owner())).andExpect(status().isOk());
        String id = JsonPath.read(mvc.perform(multipart("/api/v1/me/purchase-requests")
                        .param("product", "Cursor Pro").param("amount", "2000").param("period", "month")
                        .with(as(worker, "employee")))
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/purchase-requests/{id}/decision", id), "{\"approve\":true}")
                .with(as(worker, "employee"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/purchase-requests/{id}/decision", id), "{\"approve\":true}")
                .with(as("it@corp.ru", "it_admin"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/purchase-requests/{id}/decision", id),
                        "{\"approve\":true,\"comment\":\"до конца года\"}").with(as("f@corp.ru", "finance")))
                .andExpect(jsonPath("$.status").value("approved"));
        mvc.perform(get("/api/v1/me/notifications").with(as(worker, "employee")))
                .andExpect(jsonPath("$.items[0].text").value("Одобрено: покупка Cursor Pro: до конца года"));
        mvc.perform(get("/api/v1/me/notifications").with(as(head, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.containsString("Одобрено: покупка Cursor Pro")));
        mvc.perform(json(put("/api/v1/purchase-requests/{id}/decision", id), "{\"approve\":false}")
                .with(as("f@corp.ru", "finance"))).andExpect(status().isConflict());
    }

    @Test
    void anotherEmployeeSeesNothingOfIt() throws Exception {
        String a = mail("a");
        String b = mail("b");
        employee(a);
        employee(b);
        var file = new MockMultipartFile("file", "счёт.pdf", "application/pdf", new byte[] {1, 2});
        String id = JsonPath.read(mvc.perform(multipart("/api/v1/me/purchase-requests").file(file)
                        .param("product", "Figma").param("amount", "1500").with(as(a, "employee")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/me/purchase-requests").with(as(b, "employee")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/me/purchase-requests/{id}/file", id).with(as(b, "employee")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/purchase-requests").with(as(b, "employee"))).andExpect(status().isForbidden());
    }
}
