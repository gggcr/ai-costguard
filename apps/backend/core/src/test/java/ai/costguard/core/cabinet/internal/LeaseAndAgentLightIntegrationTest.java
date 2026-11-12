package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Аренда лицензий и напоминания о продлении (ADR 0066); бюджет-светофор и решения владельца агента (ADR 0067). */
@SpringBootTest
@AutoConfigureMockMvc
class LeaseAndAgentLightIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired LeaseService leases;
    @Autowired TransactionTemplate tx;
    @Autowired ApprovalRequests approvals;
    @Autowired OperationPosting posting;

    private final UUID tenant = UUID.randomUUID();
    private String department;

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email).claim("name", email)
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
        return JsonPath.read(mvc.perform(json(post(path), body).with(owner()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String department() throws Exception {
        if (department == null) {
            department = create("/api/v1/departments", "{\"name\":\"%s\"}".formatted(UUID.randomUUID()));
        }
        return department;
    }

    private String employee(String email) throws Exception {
        return create("/api/v1/employees", "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"x\"}"
                .formatted(email, email, department()));
    }

    private String subscription(String product, int seats, LocalDate renewal) throws Exception {
        String vendor = create("/api/v1/vendors", "{\"name\":\"%s\",\"inn\":\"\",\"category\":\"SaaS\"}"
                .formatted(UUID.randomUUID()));
        return create("/api/v1/subscriptions", """
                {"vendorId":"%s","product":"%s","contractId":null,"departmentId":"%s","seats":%d,
                 "pricePerSeat":1500,"billingPeriod":"month","renewalDate":"%s"}"""
                .formatted(vendor, product, department(), seats, renewal));
    }

    private void give(String subscription, String employeeId, LocalDate lastActive) throws Exception {
        String license = JsonPath.read(mvc.perform(json(post("/api/v1/subscriptions/{id}/licenses", subscription),
                        "{\"employeeId\":\"%s\"}".formatted(employeeId)).with(as("it@corp.ru", "it_admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/licenses/{id}/activity", license),
                "{\"lastActive\":\"%s\"}".formatted(lastActive)).with(as("it@corp.ru", "it_admin")))
                .andExpect(status().isOk());
    }

    private static String mail(String who) {
        return who + "-" + UUID.randomUUID() + "@corp.ru";
    }

    private MorningResult morning(LocalDate day) {
        return tx.execute(s -> leases.run(tenant, day));
    }

    @Test
    void aLicenseLentForAWeekGoesBackToTheColleague() throws Exception {
        String miro = subscription("Miro", 1, LocalDate.now().plusMonths(3));
        String idle = mail("idle");
        String worker = mail("worker");
        String idleId = employee(idle);
        employee(worker);
        give(miro, idleId, LocalDate.now().minusDays(90));

        String request = JsonPath.read(mvc.perform(json(post("/api/v1/me/license-requests"),
                        "{\"subscriptionId\":\"%s\",\"days\":7}".formatted(miro)).with(as(worker, "employee")))
                .andExpect(jsonPath("$.status").value("pending")).andExpect(jsonPath("$.days").value(7))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(put("/api/v1/license-requests/{id}/decision", request), "{\"approve\":true}")
                .with(as("it@corp.ru", "it_admin"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/me/licenses").with(as(worker, "employee")))
                .andExpect(jsonPath("$.held[0].product").value("Miro"))
                .andExpect(jsonPath("$.borrowed[0].fromColleague").value(true))
                .andExpect(jsonPath("$.borrowed[0].endsOn").value(LocalDate.now().plusDays(7).toString()));
        mvc.perform(get("/api/v1/me/licenses").with(as(idle, "employee")))
                .andExpect(jsonPath("$.held.length()").value(0))
                .andExpect(jsonPath("$.lent[0].product").value("Miro"));
        mvc.perform(get("/api/v1/me/notifications").with(as(idle, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Ваша лицензия Miro одолжена коллеге")));

        assertThat(morning(LocalDate.now().plusDays(6)).leasesEnded()).isZero();
        mvc.perform(get("/api/v1/me/notifications").with(as(worker, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Завтра заканчивается срок лицензии Miro")));
        assertThat(morning(LocalDate.now().plusDays(7)).leasesEnded()).isEqualTo(1);

        mvc.perform(get("/api/v1/me/licenses").with(as(worker, "employee")))
                .andExpect(jsonPath("$.held.length()").value(0)).andExpect(jsonPath("$.borrowed.length()").value(0));
        mvc.perform(get("/api/v1/me/licenses").with(as(idle, "employee")))
                .andExpect(jsonPath("$.held[0].product").value("Miro")).andExpect(jsonPath("$.lent.length()").value(0));
        mvc.perform(get("/api/v1/me/notifications").with(as(idle, "employee")))
                .andExpect(jsonPath("$.items[0].text").value("Лицензия Miro вернулась к вам"));
    }

    @Test
    void aLeasedLicenseCanBeGivenBackEarly() throws Exception {
        String figma = subscription("Figma", 2, LocalDate.now().plusMonths(3));
        String anna = mail("anna");
        employee(anna);
        mvc.perform(json(post("/api/v1/me/license-requests"), "{\"subscriptionId\":\"%s\",\"days\":3}".formatted(figma))
                .with(as(anna, "employee"))).andExpect(jsonPath("$.status").value("granted"));
        String license = JsonPath.read(mvc.perform(get("/api/v1/me/licenses").with(as(anna, "employee")))
                .andExpect(jsonPath("$.borrowed[0].fromColleague").value(false))
                .andReturn().getResponse().getContentAsString(), "$.held[0].licenseId");

        mvc.perform(post("/api/v1/me/licenses/{id}/release", license).with(as(anna, "employee")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/licenses").with(as(anna, "employee")))
                .andExpect(jsonPath("$.held.length()").value(0)).andExpect(jsonPath("$.borrowed.length()").value(0))
                .andExpect(jsonPath("$.pool[?(@.product == 'Figma')].free").value(Matchers.hasItem(2)));
        mvc.perform(post("/api/v1/me/licenses/{id}/release", license).with(as(anna, "employee")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("license_not_found"));
    }

    @Test
    void anIdleLicenseIsPointedOutOnceBeforeTheSubscriptionRenews() throws Exception {
        String canva = subscription("Canva", 3, LocalDate.now().plusDays(3));
        String busy = subscription("Notion", 3, LocalDate.now().plusDays(3));
        String bob = mail("bob");
        String bobId = employee(bob);
        give(canva, bobId, LocalDate.now().minusDays(60));
        give(busy, bobId, LocalDate.now());

        assertThat(morning(LocalDate.now()).renewalReminders()).isEqualTo(1);
        assertThat(morning(LocalDate.now()).renewalReminders()).isZero();
        mvc.perform(get("/api/v1/me/notifications").with(as(bob, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.allOf(
                        Matchers.containsString("продлится подписка Canva"), Matchers.containsString("1 500 ₽"))));
        mvc.perform(get("/api/v1/me/licenses").with(as(bob, "employee")))
                .andExpect(jsonPath("$.held[0].renewalDate").value(LocalDate.now().plusDays(3).toString()));
    }

    private String agentOf(String ownerId) throws Exception {
        String mandate = JsonPath.read(mvc.perform(json(post("/api/v1/mandates"), """
                        {"name":"%s","models":["gpt-5"],"vendors":["OpenAI"],"maxPerOperation":500,
                         "approvalThreshold":300,"hours":"круглосуточно"}""".formatted(UUID.randomUUID()))
                        .with(as("it@corp.ru", "it_admin")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(json(post("/api/v1/agents"), """
                        {"name":"Аналитик","description":"d","framework":"LangChain","departmentId":"%s","ownerId":"%s",
                         "mandateId":"%s"}""".formatted(department(), ownerId, mandate))
                        .with(as("it@corp.ru", "it_admin")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String budget(String level, String ref, String parent, int limit) throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/budgets"), """
                        {"name":"B","level":"%s","refId":%s,"parentId":%s,"limit":%d,"currency":"RUB"}"""
                        .formatted(level, ref == null ? "null" : '"' + ref + '"',
                                parent == null ? "null" : '"' + parent + '"', limit))
                        .with(as("f@corp.ru", "finance")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theOwnerSeesTheLightAndAsksToRaiseTheLimit() throws Exception {
        String kate = mail("kate");
        String kateId = employee(kate);
        String agent = agentOf(kateId);
        String company = budget("company", null, null, 100000);
        String dept = budget("department", department(), company, 10000);
        budget("agent", agent, dept, 1000);

        mvc.perform(get("/api/v1/me/agents").with(as(kate, "employee")))
                .andExpect(jsonPath("$.agents[0].light").value("green")).andExpect(jsonPath("$.agents[0].percent").value(0));
        posting.charge(tenant, UUID.randomUUID(), UUID.fromString(agent), new BigDecimal("850"), Instant.now());
        mvc.perform(get("/api/v1/me/agents").with(as(kate, "employee")))
                .andExpect(jsonPath("$.agents[0].light").value("yellow")).andExpect(jsonPath("$.agents[0].percent").value(85));
        posting.charge(tenant, UUID.randomUUID(), UUID.fromString(agent), new BigDecimal("150"), Instant.now());
        mvc.perform(get("/api/v1/me/agents").with(as(kate, "employee")))
                .andExpect(jsonPath("$.agents[0].light").value("red"));

        String request = JsonPath.read(mvc.perform(json(post("/api/v1/me/agents/{id}/limit-requests", agent),
                        "{\"add\":500,\"reason\":\"квартальный отчёт\"}").with(as(kate, "employee")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/me/agents/{id}/limit-requests", agent),
                "{\"add\":100,\"reason\":\"ещё\"}").with(as(kate, "employee")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("limit_asked"));
        // somebody else's agent is not mine
        String other = mail("other");
        employee(other);
        mvc.perform(json(post("/api/v1/me/agents/{id}/limit-requests", agent), "{\"add\":1,\"reason\":\"x\"}")
                .with(as(other, "employee"))).andExpect(status().isNotFound());
        // the employee cannot decide as finance
        mvc.perform(json(put("/api/v1/limit-requests/{id}/decision", request), "{\"approve\":true}")
                .with(as(kate, "employee"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/limit-requests/{id}/decision", request), "{\"approve\":true}")
                .with(as("f@corp.ru", "finance"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        mvc.perform(get("/api/v1/me/agents").with(as(kate, "employee")))
                .andExpect(jsonPath("$.agents[0].limit").value(1500))
                .andExpect(jsonPath("$.agents[0].light").value("green"));
        mvc.perform(get("/api/v1/me/notifications").with(as(kate, "employee")))
                .andExpect(jsonPath("$.items[0].text").value(Matchers.startsWith("Лимит агента «Аналитик» продлён: теперь 1 500 ₽")));
    }

    @Test
    void theOwnerConfirmsOrBlocksAHeldOperation() throws Exception {
        String lena = mail("lena");
        String lenaId = employee(lena);
        UUID agent = UUID.fromString(agentOf(lenaId));
        UUID first = approvals.open(tenant, UUID.randomUUID(), agent, "llm unknown-api", new BigDecimal("40"), "risk");
        UUID second = approvals.open(tenant, UUID.randomUUID(), agent, "llm openai gpt-5", new BigDecimal("350"),
                "above_threshold");

        mvc.perform(get("/api/v1/me/agents").with(as(lena, "employee")))
                .andExpect(jsonPath("$.agents[0].light").value("yellow"))
                .andExpect(jsonPath("$.agents[0].waiting.length()").value(2));
        String stranger = mail("stranger");
        employee(stranger);
        mvc.perform(json(put("/api/v1/me/agents/operations/{id}/decision", first), "{\"approve\":true}")
                .with(as(stranger, "employee"))).andExpect(status().isNotFound());

        mvc.perform(json(put("/api/v1/me/agents/operations/{id}/decision", first), "{\"approve\":true}")
                        .with(as(lena, "employee"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.notaryHash").value(Matchers.matchesPattern("[0-9a-f]{64}")));
        mvc.perform(json(put("/api/v1/me/agents/operations/{id}/decision", second),
                        "{\"approve\":false,\"comment\":\"не мой запрос\"}").with(as(lena, "employee")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.notaryHash").value(Matchers.nullValue()));
        mvc.perform(json(put("/api/v1/me/agents/operations/{id}/decision", second), "{\"approve\":true}")
                .with(as(lena, "employee"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("operation_decided"));
        mvc.perform(get("/api/v1/me/agents").with(as(lena, "employee")))
                .andExpect(jsonPath("$.agents[0].waiting.length()").value(0))
                .andExpect(jsonPath("$.agents[0].light").value("green"));
    }
}
