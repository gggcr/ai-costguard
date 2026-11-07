package ai.costguard.core.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.platform.tenant.TenantSession;
import com.jayway.jsonpath.JsonPath;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The act made at every issue, and devices and dismissals that come from connectors. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(HandoverActIntegrationTest.FixedClock.class)
class HandoverActIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired NormalizedEntities entities;
    @Autowired JdbcClient jdbc;
    @Autowired TenantSession session;
    @Autowired PlatformTransactionManager transactions;

    private final UUID tenant = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-06T12:00:00Z");
    private String department;

    private RequestPostProcessor as(UUID tenantId) {
        return jwt().jwt(j -> j.subject("kc-1").claim("tenant_id", tenantId.toString())
                        .claim("name", "Петрова Анна Сергеевна").claim("email", "petrova@corp.ru")
                        .claim("realm_access", Map.of("roles", List.of("it_admin"))))
                .authorities(realmRoles);
    }

    private String send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(as(tenant)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private String id(String body) {
        return JsonPath.read(body, "$.id");
    }

    @BeforeEach
    void company() throws Exception {
        send(put("/api/v1/company"), "{\"name\":\"ООО «Ромашка»\",\"currency\":\"RUB\"}");
        department = id(send(post("/api/v1/departments"), "{\"name\":\"Отдел разработки\"}"));
    }

    private String hire(String name, String email) throws Exception {
        return id(send(post("/api/v1/employees"), """
                {"name":"%s","email":"%s","departmentId":"%s","position":"Ведущий инженер"}
                """.formatted(name, email, department)));
    }

    private String laptop(String inventory, String serial) throws Exception {
        return id(send(post("/api/v1/assets"), """
                {"inventoryNumber":"%s","model":"ThinkPad T14 Gen 5","type":"LAPTOP","cost":80000.00,
                 "serialNumber":%s}
                """.formatted(inventory, serial == null ? "null" : "\"" + serial + "\"")));
    }

    private String issue(String asset, String employee, String returnDue) throws Exception {
        return send(put("/api/v1/assets/{id}/assignment", asset), """
                {"employeeId":"%s","returnDue":%s}
                """.formatted(employee, returnDue == null ? "null" : "\"" + returnDue + "\""));
    }

    private byte[] act(String asset, String assignment) throws Exception {
        return mvc.perform(get("/api/v1/assets/{id}/assignments/{a}/act", asset, assignment).with(as(tenant)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    void anIssueMakesItsHandoverAct() throws Exception {
        String ivanov = hire("Иванов Иван Иванович", "ivanov@corp.ru");
        String asset = laptop("INV-0042", "PF4ABC12");

        String issued = issue(asset, ivanov, "2027-01-31");
        assertThat((String) JsonPath.read(issued, "$.actNumber")).isEqualTo("АВ-2026-0001");

        var response = mvc.perform(get("/api/v1/assets/{id}/assignments/{a}/act", asset, id(issued))
                        .with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse();
        byte[] pdf = response.getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(response.getHeader("X-Content-SHA256"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));

        String text = text(pdf);
        assertThat(text).contains("АКТ № АВ-2026-0001", "приёма-передачи оборудования", "6 ноября 2026 г.",
                "ООО «Ромашка»", "Иванов Иван Иванович", "Ведущий инженер", "Отдел разработки",
                "ThinkPad T14 Gen 5", "Ноутбук", "INV-0042", "PF4ABC12", "80 000,00", "Срок возврата: 31 января 2027 г. 1.",
                "Петрова А. С.", "Иванов И. И.");
    }

    @Test
    void actNumbersRunPerTenantAndARepeatedIssueMakesNoNewAct() throws Exception {
        String ivanov = hire("Иванов Иван", "ivanov@corp.ru");
        String first = laptop("INV-1", null);
        String second = laptop("INV-2", null);

        String issued = issue(first, ivanov, null);
        String repeated = issue(first, ivanov, "2027-06-30");
        assertThat((String) JsonPath.read(repeated, "$.actNumber")).isEqualTo("АВ-2026-0001");
        assertThat((String) JsonPath.read(issue(second, ivanov, null), "$.actNumber")).isEqualTo("АВ-2026-0002");
        mvc.perform(get("/api/v1/assets/{id}/assignments", first).with(as(tenant)))
                .andExpect(jsonPath("$[0].actNumber").value("АВ-2026-0001"));
        mvc.perform(get("/api/v1/assets/{id}", first).with(as(tenant)))
                .andExpect(jsonPath("$.currentAssignment.actNumber").value("АВ-2026-0001"));

        // the act stays as made: the later return date is not in it
        assertThat(text(act(first, id(issued)))).doesNotContain("30 июня 2027").contains("Срок возврата не установлен");

        // returned and issued again: a new act
        mvc.perform(delete("/api/v1/assets/{id}/assignment", first).with(as(tenant)));
        assertThat((String) JsonPath.read(issue(first, ivanov, null), "$.actNumber")).isEqualTo("АВ-2026-0003");
    }

    @Test
    void anIssueToSomeoneNotInTheOrgStructureIsRejected() throws Exception {
        String asset = laptop("INV-9", null);

        mvc.perform(put("/api/v1/assets/{id}/assignment", asset).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"%s\"}".formatted(UUID.randomUUID())).with(as(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("employee_not_found"));
        mvc.perform(get("/api/v1/assets/{id}", asset).with(as(tenant)))
                .andExpect(jsonPath("$.status").value("IN_STOCK"));
    }

    @Test
    void anActIsNotFoundForAnotherAssetOrTenant() throws Exception {
        String ivanov = hire("Иванов Иван", "ivanov@corp.ru");
        String asset = laptop("INV-1", null);
        String other = laptop("INV-2", null);
        String issued = id(issue(asset, ivanov, null));

        mvc.perform(get("/api/v1/assets/{id}/assignments/{a}/act", other, issued).with(as(tenant)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("act_not_found"));
        mvc.perform(get("/api/v1/assets/{id}/assignments/{a}/act", asset, issued).with(as(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("asset_not_found"));
    }

    @Test
    void anActCannotBeChangedOrDeleted() throws Exception {
        String ivanov = hire("Иванов Иван", "ivanov@corp.ru");
        issue(laptop("INV-1", null), ivanov, null);
        var tx = new TransactionTemplate(transactions);

        for (String change : List.of("update asset_act set number = 'X'", "delete from asset_act")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                session.bind(tenant);
                jdbc.sql(change).update();
            })).hasMessageContaining("immutable");
        }
        List<String> numbers = tx.execute(s -> {
            session.bind(tenant);
            return jdbc.sql("select number from asset_act").query(String.class).list();
        });
        assertThat(numbers).containsExactly("АВ-2026-0001");
    }

    @Test
    void aSerialNumberIsUniqueWithinTheTenant() throws Exception {
        laptop("INV-1", "SN-1");
        mvc.perform(post("/api/v1/assets").contentType(MediaType.APPLICATION_JSON).content("""
                        {"inventoryNumber":"INV-2","model":"X","type":"LAPTOP","cost":1,"serialNumber":"SN-1"}
                        """).with(as(tenant)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("serial_number_taken"));
    }

    private UUID report(String key, String payload) {
        return entities.received(tenant, UUID.randomUUID(), key, "ASSET", payload, 1.0, now);
    }

    @Test
    void aDeviceFromAnMdmIsRegisteredAndIssuedToItsHolderWithAnAct() throws Exception {
        String ivanov = hire("Иванов Иван", "ivanov@corp.ru");
        String petrov = hire("Петров Пётр", "petrov@corp.ru");

        UUID asset = report("jamf:C02X", """
                {"serial_number":"C02X","model":"MacBook Pro 14","type":"LAPTOP","holder_email":"IVANOV@corp.ru",
                 "source":"jamf"}""");
        assertThat(asset).isNotNull();
        String body = mvc.perform(get("/api/v1/assets/{id}", asset).with(as(tenant)))
                .andExpect(jsonPath("$.inventoryNumber").value("C02X"))
                .andExpect(jsonPath("$.serialNumber").value("C02X"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.cost").value(0.0))
                .andExpect(jsonPath("$.currentAssignment.employeeId").value(ivanov))
                .andExpect(jsonPath("$.currentAssignment.actNumber").value("АВ-2026-0001"))
                .andReturn().getResponse().getContentAsString();
        String text = text(act(asset.toString(), JsonPath.read(body, "$.currentAssignment.id")));
        assertThat(text).contains("по данным jamf", "Иванов Иван").doesNotContain("Петрова");

        // the same report again changes nothing; accounting adds the cost by the inventory number
        assertThat(report("jamf:C02X", """
                {"serial_number":"C02X","model":"MacBook Pro 14","type":"LAPTOP","holder_email":"ivanov@corp.ru",
                 "source":"jamf"}""")).isEqualTo(asset);
        assertThat(report("onec:C02X", """
                {"inventory_number":"C02X","cost":"215 000,50","source":"onec"}""")).isEqualTo(asset);
        mvc.perform(get("/api/v1/assets/{id}/assignments", asset).with(as(tenant)))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/assets/{id}", asset).with(as(tenant)))
                .andExpect(jsonPath("$.cost").value(215000.50))
                .andExpect(jsonPath("$.model").value("MacBook Pro 14"));

        // the MDM moved it to Petrov: Ivanov returned it, Petrov got it with a new act
        report("jamf:C02X", """
                {"serial_number":"C02X","model":"MacBook Pro 14","type":"LAPTOP","holder_name":"петров  пётр",
                 "source":"jamf"}""");
        mvc.perform(get("/api/v1/assets/{id}/assignments", asset).with(as(tenant)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.employeeId == '%s')].returnedAt".formatted(ivanov)).value("2026-11-06"))
                .andExpect(jsonPath("$[?(@.employeeId == '%s')].actNumber".formatted(petrov)).value("АВ-2026-0002"));

        // no holder in the report: the holder stays
        report("jamf:C02X", "{\"serial_number\":\"C02X\",\"source\":\"jamf\"}");
        mvc.perform(get("/api/v1/assets/{id}", asset).with(as(tenant)))
                .andExpect(jsonPath("$.currentAssignment.employeeId").value(petrov));
    }

    @Test
    void aDeviceWithoutNumbersOrAnUnknownHolderIsNotIssued() throws Exception {
        assertThat(report("x", "{\"model\":\"Без номера\",\"source\":\"intune\"}")).isNull();
        UUID asset = report("intune:SN-7", """
                {"serial_number":"SN-7","model":"Surface Laptop","type":"TOASTER","holder_email":"nobody@corp.ru",
                 "source":"intune"}""");
        mvc.perform(get("/api/v1/assets/{id}", asset).with(as(tenant)))
                .andExpect(jsonPath("$.type").value("OTHER"))
                .andExpect(jsonPath("$.status").value("IN_STOCK"));
    }

    @Test
    void anHrSystemDismissesAndADirectoryDoesNot() throws Exception {
        String ivanov = hire("Иванов Иван", "ivanov@corp.ru");
        String petrov = hire("Петров Пётр", "petrov@corp.ru");

        entities.received(tenant, UUID.randomUUID(), "p1", "EMPLOYEE",
                "{\"email\":\"petrov@corp.ru\",\"active\":false,\"source\":\"yandex360\"}", 1.0, now);
        entities.received(tenant, UUID.randomUUID(), "p2", "EMPLOYEE",
                "{\"email\":\"ivanov@corp.ru\",\"active\":false,\"dismissed\":true,\"source\":\"bitrix24\"}", 1.0, now);

        mvc.perform(get("/api/v1/employees/{id}", ivanov).with(as(tenant)))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.dismissedAt").value("2026-11-06"));
        mvc.perform(get("/api/v1/employees/{id}", petrov).with(as(tenant)))
                .andExpect(jsonPath("$.active").value(true));
    }
}
