package ai.costguard.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.org.Staff;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class ActivityLogIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired Staff staff;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor as(String subject, String email, String name, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", tenant.toString()).claim("email", email)
                .claim("name", name).claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private RequestPostProcessor owner() {
        return as("kc-owner", "owner@romashka.ru", "Анна Владелец", "owner");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String id(MockHttpServletRequestBuilder request, RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn()
                .getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aBudgetChangeIsLoggedWithWhoDidItAndWhatChanged() throws Exception {
        String budget = id(json(post("/api/v1/budgets"),
                "{\"name\":\"B\",\"level\":\"company\",\"limit\":1000,\"currency\":\"RUB\"}"), owner());
        mvc.perform(json(put("/api/v1/budgets/{id}/limit", budget), "{\"limit\":2500}").with(owner()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/activity").param("targetId", budget)
                        .with(as("kc-auditor", "audit@romashka.ru", "Аудитор", "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].action").value("budget.limit_changed"))
                .andExpect(jsonPath("$[0].details.from").value("1000"))
                .andExpect(jsonPath("$[0].details.to").value("2500"))
                .andExpect(jsonPath("$[0].actor.email").value("owner@romashka.ru"))
                .andExpect(jsonPath("$[0].actor.name").value("Анна Владелец"))
                .andExpect(jsonPath("$[0].actor.erased").value(false))
                .andExpect(jsonPath("$[1].action").value("budget.created"));
    }

    @Test
    void rolesMandatesAndEquipmentAreLogged() throws Exception {
        String department = id(json(post("/api/v1/departments"), "{\"name\":\"ИТ\"}"), owner());
        String employee = id(json(post("/api/v1/employees"), """
                {"name":"Петров Иван Сергеевич","email":"petrov@romashka.ru","departmentId":"%s","position":"Инженер"}
                """.formatted(department)), owner());
        String user = id(json(post("/api/v1/users"), """
                {"name":"Петров","email":"petrov@romashka.ru","role":"finance","employeeId":"%s"}
                """.formatted(employee)), owner());
        mvc.perform(json(put("/api/v1/users/{id}/role", user), "{\"role\":\"auditor\"}").with(owner()))
                .andExpect(status().isOk());
        String mandate = id(json(post("/api/v1/mandates"), """
                {"name":"M","models":["gpt-5"],"vendors":["OpenAI"],"maxPerOperation":500,"approvalThreshold":300,
                 "hours":"09:00–21:00"}"""), owner());
        mvc.perform(put("/api/v1/mandates/{id}/publication", mandate).with(owner())).andExpect(status().isOk());
        String asset = id(json(post("/api/v1/assets"), """
                {"inventoryNumber":"INV-7","model":"ThinkPad","type":"LAPTOP","cost":80000}"""), owner());
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", asset), "{\"employeeId\":\"%s\"}".formatted(employee))
                .with(owner())).andExpect(status().isOk());

        String log = mvc.perform(get("/api/v1/activity").param("limit", "200").with(owner()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> actions = JsonPath.read(log, "$[*].action");
        assertThat(actions).contains("employee.created", "user.created", "user.role_changed", "mandate.created",
                "mandate.published", "asset.issued");
        assertThat(JsonPath.<List<String>>read(log, "$[?(@.action == 'user.role_changed')].details.to"))
                .containsExactly("auditor");
        assertThat(JsonPath.<List<String>>read(log, "$[?(@.action == 'asset.issued')].details.employeeId"))
                .containsExactly(employee);
        // Details point at people by id: the name and e-mail of the employee are nowhere in them.
        assertThat(JsonPath.<List<Object>>read(log, "$[*].details").toString()).doesNotContain("Петров", "petrov@");
    }

    @Test
    void whatAConnectorDidIsLoggedAsTheSystem() throws Exception {
        String department = id(json(post("/api/v1/departments"), "{\"name\":\"Склад\"}"), owner());
        String employee = id(json(post("/api/v1/employees"), """
                {"name":"С","email":"s@romashka.ru","departmentId":"%s","position":"П"}""".formatted(department)),
                owner());
        tx.executeWithoutResult(s -> staff.dismissedInHrSystem(tenant, UUID.fromString(employee)));

        mvc.perform(get("/api/v1/activity").param("action", "employee.dismissed").with(owner()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].system").value("hr-system"))
                .andExpect(jsonPath("$[0].actor").doesNotExist());
    }

    @Test
    void onlyTheOwnerAndTheAuditorReadTheLog() throws Exception {
        mvc.perform(get("/api/v1/activity").with(as("kc-f", "f@romashka.ru", "Ф", "finance")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("role_required"));
        mvc.perform(get("/api/v1/activity").param("limit", "500").with(owner()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_limit"));
    }

    @Test
    void theDatabaseRefusesToChangeOrDeleteAnEntry() throws Exception {
        id(json(post("/api/v1/budgets"), "{\"name\":\"B\",\"level\":\"company\",\"limit\":1,\"currency\":\"RUB\"}"),
                owner());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            bind();
            jdbc.sql("update activity_entry set action = 'nothing'").update();
        })).hasMessageContaining("append-only");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            bind();
            jdbc.sql("delete from activity_entry").update();
        })).hasMessageContaining("append-only");
    }

    private void bind() {
        jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenant.toString()).query(String.class).single();
    }
}
