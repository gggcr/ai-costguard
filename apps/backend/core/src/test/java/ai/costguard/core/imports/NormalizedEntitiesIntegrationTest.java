package ai.costguard.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.names.Names;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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

@SpringBootTest
@AutoConfigureMockMvc
class NormalizedEntitiesIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired NormalizedEntities entities;

    final UUID tenant = UUID.randomUUID();
    final UUID source = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-07T12:00:00Z");

    RequestPostProcessor owner() {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of("owner")))).authorities(realmRoles);
    }

    String create(String path, String body) throws Exception {
        return JsonPath.read(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(owner())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    UUID receive(String key, String type, String payload, Instant at) {
        return entities.received(tenant, source, key, type, payload, 1.0, at);
    }

    @Test
    void namesAreCleanedLikeInPython() {
        assertThat(Names.clean("ООО «Ромашка»")).isEqualTo("ромашка");
        assertThat(Names.clean("Ромашка, ООО")).isEqualTo("ромашка");
        assertThat(Names.clean("Acme LLC")).isEqualTo("acme");
        assertThat(Names.inn("77 0000 0001")).isEqualTo("7700000001");
        assertThat(Names.inn("123")).isNull();
    }

    @Test
    void vendorsMergeByInnThenByName() throws Exception {
        String typed = create("/api/v1/vendors", "{\"name\":\"Ромашка\",\"inn\":\"\",\"category\":\"SaaS\"}");

        UUID fromOneC = receive("k1", "VENDOR", "{\"name\":\"ООО «Ромашка»\",\"inn\":\"7700000001\"}", now);
        UUID fromInvoice = receive("k2", "VENDOR", "{\"name\":\"Другое имя\",\"inn\":\"7700000001\"}", now);
        UUID fresh = receive("k3", "VENDOR", "{\"name\":\"Новый поставщик\"}", now);

        assertThat(fromOneC).isEqualTo(UUID.fromString(typed));
        assertThat(fromInvoice).isEqualTo(fromOneC);
        assertThat(fresh).isNotNull().isNotEqualTo(fromOneC);
        mvc.perform(get("/api/v1/vendors").with(owner())).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '%s')].inn".formatted(typed)).value("7700000001"));
    }

    @Test
    void licensesAreTiedAndActivityOnlyMovesForward() throws Exception {
        String department = create("/api/v1/departments", "{\"name\":\"ИТ\"}");
        String employee = create("/api/v1/employees", """
                {"name":"Иванов Иван","email":"ivanov@corp.ru","departmentId":"%s","position":"Dev"}
                """.formatted(department));
        String vendor = create("/api/v1/vendors", "{\"name\":\"Битрикс24\",\"inn\":\"\",\"category\":\"\"}");
        String sub = create("/api/v1/subscriptions", """
                {"vendorId":"%s","product":"Bitrix24","contractId":null,"departmentId":"%s","seats":1,
                 "pricePerSeat":500,"billingPeriod":"month","renewalDate":"2027-01-01"}
                """.formatted(vendor, department));

        assertThat(receive("p1", "EMPLOYEE", "{\"email\":\"IVANOV@corp.ru\",\"name\":\"x\"}", now))
                .isEqualTo(UUID.fromString(employee));
        assertThat(receive("p2", "EMPLOYEE", "{\"email\":null,\"name\":\"иванов  иван\"}", now))
                .isEqualTo(UUID.fromString(employee));
        assertThat(receive("p3", "EMPLOYEE", "{\"email\":\"nobody@corp.ru\",\"name\":\"Никто\"}", now)).isNull();

        String license = """
                {"vendor":"Битрикс24","product":"bitrix24","employee":"ivanov@corp.ru","last_active":"%s"}""";
        UUID seat = receive("l1", "LICENSE_ASSIGNMENT", license.formatted("2026-10-01"), now);
        assertThat(seat).isNotNull();
        receive("l1", "LICENSE_ASSIGNMENT", license.formatted("2026-09-01"), now.plusSeconds(60));
        mvc.perform(get("/api/v1/subscriptions/" + sub + "/licenses").with(owner()))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].lastActive").value("2026-10-01"));

        // no free seat: the core does not invent one
        create("/api/v1/employees", """
                {"name":"Петров","email":"petrov@corp.ru","departmentId":"%s","position":"Dev"}
                """.formatted(department));
        assertThat(receive("l2", "LICENSE_ASSIGNMENT",
                "{\"vendor\":\"Битрикс24\",\"product\":\"Bitrix24\",\"employee\":\"petrov@corp.ru\"}", now)).isNull();
    }

    @Test
    void anOlderReportChangesNothing() {
        assertThat(receive("v", "VENDOR", "{\"name\":\"Акме\"}", now)).isNotNull();
        assertThat(receive("v", "VENDOR", "{\"name\":\"Акме старое\"}", now.minusSeconds(60))).isNull();
    }

    @Test
    void otherTenantsSeeNothing() throws Exception {
        receive("v", "VENDOR", "{\"name\":\"Секретный\"}", now);
        RequestPostProcessor stranger = jwt().jwt(j -> j.claim("tenant_id", UUID.randomUUID().toString())
                .claim("email", "s@x.ru").claim("realm_access", Map.of("roles", List.of("owner"))))
                .authorities(realmRoles);
        mvc.perform(get("/api/v1/vendors").with(stranger)).andExpect(jsonPath("$", hasSize(0)));
    }
}
