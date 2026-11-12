package ai.costguard.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Who a connector's record names (audit of 2026-11-13, the R1.5.1 rule for connectors): an HR system that dismisses
 * and an MDM that issues a device with an act must not reach another person through the name when the address they
 * sent is wrong, unknown or shared. An address that is given decides alone; only a record without an address is
 * matched by the name, and only to the one employee with that name. A wrong dismissal also disables that person's
 * Keycloak account and mailbox (Offboarding), so it is not a display matter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntityStagingIdentityIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired NormalizedEntities entities;

    private final UUID tenant = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Instant now = Instant.parse("2026-11-06T12:00:00Z");
    private String department;

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("kc-it").claim("tenant_id", tenant.toString())
                        .claim("email", "it@romashka.example")
                        .claim("realm_access", Map.of("roles", List.of("it_admin"))))
                .authorities(realmRoles);
    }

    private String send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(admin()))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    @BeforeEach
    void company() throws Exception {
        send(put("/api/v1/company"), "{\"name\":\"ООО «Ромашка»\",\"currency\":\"RUB\"}");
        department = JsonPath.read(send(post("/api/v1/departments"), "{\"name\":\"Отдел\"}"), "$.id");
    }

    private String hire(String name, String email) throws Exception {
        return JsonPath.read(send(post("/api/v1/employees"), """
                {"name":"%s","email":"%s","departmentId":"%s","position":"Инженер"}
                """.formatted(name, email, department)), "$.id");
    }

    private String mail(String local) {
        return local + "-" + run + "@romashka.example";
    }

    private UUID report(String key, String type, String payload) {
        return entities.received(tenant, UUID.randomUUID(), key, type, payload, 1.0, now);
    }

    private UUID dismissal(String key, String email, String name) {
        return report(key, "EMPLOYEE", """
                {"email":%s,"name":%s,"active":false,"dismissed":true,"source":"bitrix24"}
                """.formatted(email == null ? "null" : "\"" + email + "\"", name == null ? "null" : "\"" + name + "\""));
    }

    private boolean active(String employee) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/employees/{id}", employee).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.active");
    }

    private UUID device(String key, String serial, String email, String name) {
        return report(key, "ASSET", """
                {"serial_number":"%s","model":"ThinkPad","type":"LAPTOP","holder_email":%s,"holder_name":%s,
                 "source":"jamf"}""".formatted(serial, email == null ? "null" : "\"" + email + "\"",
                name == null ? "null" : "\"" + name + "\""));
    }

    /** Who holds the device; null when it is not issued (no current assignment). */
    private Object holder(UUID asset) throws Exception {
        Map<String, Object> current = JsonPath.read(mvc.perform(get("/api/v1/assets/{id}", asset).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.currentAssignment");
        return current == null ? null : current.get("employeeId");
    }

    private String state(UUID asset) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/assets/{id}", asset).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.status");
    }

    @Test
    void anUnknownAddressDoesNotDismissTheEmployeeWithTheName() throws Exception {
        String petrov = hire("Петров Пётр", mail("petrov"));

        assertThat(dismissal("hr-1", mail("typo"), "Петров Пётр")).isNull();

        assertThat(active(petrov)).as("dismissed (and locked out) through the name").isTrue();
    }

    @Test
    void anInvalidAddressDoesNotDismissTheEmployeeWithTheName() throws Exception {
        String petrov = hire("Петров Пётр", mail("petrov"));

        assertThat(dismissal("hr-1", "не адрес", "Петров Пётр")).isNull();

        assertThat(active(petrov)).isTrue();
    }

    @Test
    void anAddressOfTwoEmployeesIsNotRescuedByTheName() throws Exception {
        String lower = hire("Петров Пётр", mail("shared"));
        String upper = hire("Сидоров Сидор", mail("SHARED"));

        assertThat(dismissal("hr-1", mail("shared"), "Петров Пётр")).isNull();

        assertThat(active(lower)).isTrue();
        assertThat(active(upper)).isTrue();
    }

    @Test
    void namesakesWithoutAnAddressAreNotChosenBetween() throws Exception {
        String first = hire("Иванов Иван", mail("ivanov1"));
        String second = hire("Иванов Иван", mail("ivanov2"));

        assertThat(dismissal("hr-1", null, "иванов  иван")).isNull();

        assertThat(active(first)).isTrue();
        assertThat(active(second)).isTrue();
    }

    @Test
    void theRightAddressOrTheOnlyNameWithoutAnAddressStillDismisses() throws Exception {
        String petrov = hire("Петров Пётр", mail("petrov"));
        String sidorov = hire("Сидоров Сидор", mail("sidorov"));

        assertThat(dismissal("hr-1", mail("PETROV"), "Кто угодно")).isEqualTo(UUID.fromString(petrov));
        assertThat(dismissal("hr-2", null, "сидоров  сидор")).isEqualTo(UUID.fromString(sidorov));

        assertThat(active(petrov)).isFalse();
        assertThat(active(sidorov)).isFalse();
    }

    @Test
    void aDeviceWithAWrongAddressIsNotIssuedToTheEmployeeWithTheName() throws Exception {
        hire("Петров Пётр", mail("petrov"));
        hire("Сидоров Сидор", mail("dup"));
        hire("Козлов Козьма", mail("DUP"));

        UUID unknown = device("jamf:A1", "A1", mail("typo"), "Петров Пётр");
        UUID invalid = device("jamf:A2", "A2", "не адрес", "Петров Пётр");
        UUID shared = device("jamf:A3", "A3", mail("dup"), "Сидоров Сидор");

        for (UUID asset : List.of(unknown, invalid, shared)) {
            assertThat(asset).isNotNull();
            assertThat(state(asset)).as("issued with an act to someone the source did not name").isEqualTo("IN_STOCK");
            assertThat(holder(asset)).isNull();
        }
    }

    @Test
    void aDeviceWithoutAnAddressGoesToTheOnlyEmployeeWithTheName() throws Exception {
        String petrov = hire("Петров Пётр", mail("petrov"));
        hire("Иванов Иван", mail("ivanov1"));
        hire("Иванов Иван", mail("ivanov2"));

        assertThat(holder(device("jamf:B1", "B1", null, "петров  пётр"))).isEqualTo(petrov);
        UUID namesakes = device("jamf:B2", "B2", null, "Иванов Иван");
        assertThat(state(namesakes)).isEqualTo("IN_STOCK");
    }

    @Test
    void aRepeatedReportStaysUnmatchedAndTheCorrectedOneMatches() throws Exception {
        String petrov = hire("Петров Пётр", mail("petrov"));

        assertThat(report("hr-1", "EMPLOYEE", """
                {"email":"%s","name":"Петров Пётр","dismissed":true,"source":"bitrix24"}""".formatted(mail("typo"))))
                .isNull();
        assertThat(entities.received(tenant, UUID.randomUUID(), "hr-1", "EMPLOYEE", """
                {"email":"%s","name":"Петров Пётр","dismissed":true,"source":"bitrix24"}""".formatted(mail("typo")),
                1.0, now.plusSeconds(60))).isNull();
        assertThat(active(petrov)).isTrue();

        assertThat(entities.received(tenant, UUID.randomUUID(), "hr-1", "EMPLOYEE", """
                {"email":"%s","name":"Петров Пётр","dismissed":true,"source":"bitrix24"}""".formatted(mail("petrov")),
                1.0, now.plusSeconds(120))).isEqualTo(UUID.fromString(petrov));
        assertThat(active(petrov)).isFalse();
    }
}
