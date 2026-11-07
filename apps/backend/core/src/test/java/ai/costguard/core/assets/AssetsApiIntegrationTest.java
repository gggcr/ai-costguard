package ai.costguard.core.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(AssetsApiIntegrationTest.FixedClock.class)
class AssetsApiIntegrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final UUID employee = UUID.randomUUID();

    private RequestPostProcessor as(UUID tenantId) {
        return as(tenantId, "it_admin");
    }

    /** Goes through the real role converter, so a token here looks like one from Keycloak. */
    private RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String laptop(String inventoryNumber) {
        return """
                {"inventoryNumber":"%s","model":"ThinkPad T14","type":"LAPTOP","cost":80000.00}
                """.formatted(inventoryNumber);
    }

    private static String inventoryNumber() {
        return UUID.randomUUID().toString();
    }

    private String createAsset() throws Exception {
        String body = mvc.perform(json(post("/api/v1/assets"), laptop(inventoryNumber())).with(as(tenant)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String issue(UUID employeeId) {
        return """
                {"employeeId":"%s","returnDue":"2027-01-31"}
                """.formatted(employeeId);
    }

    private void assign(String assetId, UUID employeeId) throws Exception {
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", assetId), issue(employeeId)).with(as(tenant)))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/assets")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "finance", "it_admin"})
    void managersCanChangeAssets(String role) throws Exception {
        String body = mvc.perform(json(post("/api/v1/assets"), laptop(inventoryNumber())).with(as(tenant, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(employee)).with(as(tenant, role)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/assets/{id}/assignment", id).with(as(tenant, role)))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/assets/{id}/retirement", id).with(as(tenant, role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent_owner", "auditor", "unknown"})
    void otherRolesCanReadButNotChangeAssets(String role) throws Exception {
        String id = createAsset();
        assign(id, employee);

        for (var change : List.of(
                json(post("/api/v1/assets"), laptop(inventoryNumber())),
                json(put("/api/v1/assets/{id}/assignment", id), issue(UUID.randomUUID())),
                delete("/api/v1/assets/{id}/assignment", id),
                put("/api/v1/assets/{id}/retirement", id))) {
            mvc.perform(change.with(as(tenant, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("role_required"));
        }

        mvc.perform(get("/api/v1/assets/{id}", id).with(as(tenant, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.currentAssignment.employeeId").value(employee.toString()));
        mvc.perform(get("/api/v1/assets").with(as(tenant, role))).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void tokenWithoutRolesCannotChangeAssets() throws Exception {
        mvc.perform(json(post("/api/v1/assets"), laptop(inventoryNumber())).with(as(tenant, new String[0])))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("role_required"));
    }

    @Test
    void tokenWithoutTenantIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/assets").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_missing"));
    }

    @Test
    void tokenWithMalformedTenantIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/assets").with(jwt().jwt(j -> j.claim("tenant_id", "not-a-uuid"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_missing"));
    }

    @Test
    void invalidRequestIsDescribedAsAProblem() throws Exception {
        mvc.perform(json(post("/api/v1/assets"), "{}").with(as(tenant)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void rowsAreInvisibleWithoutATenantBoundToTheTransaction() throws Exception {
        createAsset();

        assertThat(jdbc.sql("select count(*) from asset").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from asset_assignment").query(Long.class).single()).isZero();
    }

    @Test
    void concurrentIssuesToDifferentEmployeesLeaveOneHolder() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 30; round++) {
                String id = createAsset();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> attempts = Stream.generate(UUID::randomUUID).limit(2)
                        .map(person -> pool.submit(() -> {
                            start.await();
                            return mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(person))
                                    .with(as(tenant))).andReturn().getResponse().getStatus();
                        }))
                        .toList();
                start.countDown();

                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> attempt : attempts) {
                    statuses.add(attempt.get());
                }
                assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            }
        }
    }

    @Test
    void createdAssetIsInStock() throws Exception {
        String number = inventoryNumber();
        mvc.perform(json(post("/api/v1/assets"), laptop(number)).with(as(tenant)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/assets/")))
                .andExpect(jsonPath("$.inventoryNumber").value(number))
                .andExpect(jsonPath("$.model").value("ThinkPad T14"))
                .andExpect(jsonPath("$.cost").value(80000.00))
                .andExpect(jsonPath("$.status").value("IN_STOCK"))
                .andExpect(jsonPath("$.currentAssignment").value(nullValue()));
    }

    @Test
    void sameInventoryNumberIsRejectedWithinTenantButAllowedInAnother() throws Exception {
        String number = inventoryNumber();
        mvc.perform(json(post("/api/v1/assets"), laptop(number)).with(as(tenant)))
                .andExpect(status().isCreated());

        mvc.perform(json(post("/api/v1/assets"), laptop(number)).with(as(tenant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory_number_taken"));
        mvc.perform(json(post("/api/v1/assets"), laptop(number)).with(as(UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidAssetIsRejected() throws Exception {
        String negativeCost = laptop(inventoryNumber()).replace("80000.00", "-1");
        mvc.perform(json(post("/api/v1/assets"), negativeCost).with(as(tenant)))
                .andExpect(status().isBadRequest());
        String tooPrecise = laptop(inventoryNumber()).replace("80000.00", "1.234");
        mvc.perform(json(post("/api/v1/assets"), tooPrecise).with(as(tenant)))
                .andExpect(status().isBadRequest());
        String noType = laptop(inventoryNumber()).replace("\"type\":\"LAPTOP\",", "");
        mvc.perform(json(post("/api/v1/assets"), noType).with(as(tenant))).andExpect(status().isBadRequest());
        String unknownType = laptop(inventoryNumber()).replace("LAPTOP", "TOASTER");
        mvc.perform(json(post("/api/v1/assets"), unknownType).with(as(tenant))).andExpect(status().isBadRequest());
        String blankModel = laptop(inventoryNumber()).replace("ThinkPad T14", " ");
        mvc.perform(json(post("/api/v1/assets"), blankModel).with(as(tenant)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assetOfAnotherTenantLooksMissing() throws Exception {
        String id = createAsset();
        UUID stranger = UUID.randomUUID();

        mvc.perform(get("/api/v1/assets/{id}", id).with(as(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("asset_not_found"));
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(employee)).with(as(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/assets/{id}/assignment", id).with(as(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/assets/{id}/retirement", id).with(as(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/assets/{id}/assignments", id).with(as(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignedAssetShowsItsHolder() throws Exception {
        String id = createAsset();
        assign(id, employee);

        mvc.perform(get("/api/v1/assets/{id}", id).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.currentAssignment.employeeId").value(employee.toString()))
                .andExpect(jsonPath("$.currentAssignment.returnDue").value("2027-01-31"))
                .andExpect(jsonPath("$.currentAssignment.issuedAt").value("2026-11-06"));
    }

    @Test
    void returnDateIsOptional() throws Exception {
        String id = createAsset();
        String permanent = "{\"employeeId\":\"%s\"}".formatted(employee);

        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), permanent).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnDue").value(nullValue()));
    }

    @Test
    void repeatingAnAssignmentChangesNothing() throws Exception {
        String id = createAsset();
        String first = mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(employee)).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(employee.toString()))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(employee)).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));

        mvc.perform(get("/api/v1/assets/{id}/assignments", id).with(as(tenant)))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void assigningToTheSameEmployeeAgainMovesTheReturnDate() throws Exception {
        String id = createAsset();
        assign(id, employee);

        String extended = "{\"employeeId\":\"%s\",\"returnDue\":\"2027-06-30\"}".formatted(employee);
        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), extended).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnDue").value("2027-06-30"));
        mvc.perform(get("/api/v1/assets/{id}/assignments", id).with(as(tenant)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].returnDue").value("2027-06-30"));
    }

    @Test
    void assetHeldBySomeoneElseCannotBeAssigned() throws Exception {
        String id = createAsset();
        assign(id, employee);

        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(UUID.randomUUID())).with(as(tenant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("asset_already_assigned"));
    }

    @Test
    void returnedAssetGoesBackToStockAndKeepsHistory() throws Exception {
        String id = createAsset();
        assign(id, employee);

        mvc.perform(delete("/api/v1/assets/{id}/assignment", id).with(as(tenant)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/assets/{id}/assignment", id).with(as(tenant)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/assets/{id}", id).with(as(tenant)))
                .andExpect(jsonPath("$.status").value("IN_STOCK"))
                .andExpect(jsonPath("$.currentAssignment").value(nullValue()));
        mvc.perform(get("/api/v1/assets/{id}/assignments", id).with(as(tenant)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].returnedAt").value("2026-11-06"));
    }

    @Test
    void returnedAssetCanBeIssuedToSomeoneElse() throws Exception {
        String id = createAsset();
        assign(id, employee);
        mvc.perform(delete("/api/v1/assets/{id}/assignment", id).with(as(tenant)));

        assign(id, UUID.randomUUID());

        mvc.perform(get("/api/v1/assets/{id}/assignments", id).with(as(tenant)))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void assignedAssetCannotBeRetired() throws Exception {
        String id = createAsset();
        assign(id, employee);

        mvc.perform(put("/api/v1/assets/{id}/retirement", id).with(as(tenant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("asset_already_assigned"));
    }

    @Test
    void retirementIsRepeatableAndBlocksAssignment() throws Exception {
        String id = createAsset();

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/v1/assets/{id}/retirement", id).with(as(tenant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RETIRED"));
        }

        mvc.perform(json(put("/api/v1/assets/{id}/assignment", id), issue(employee)).with(as(tenant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("asset_retired"));
    }

    @Test
    void listIsScopedToTenantAndFilteredByStatus() throws Exception {
        String stock = createAsset();
        String held = createAsset();
        String retired = createAsset();
        assign(held, employee);
        mvc.perform(put("/api/v1/assets/{id}/retirement", retired).with(as(tenant)));

        for (var expected : new String[][] {{"IN_STOCK", stock}, {"ASSIGNED", held}, {"RETIRED", retired}}) {
            mvc.perform(get("/api/v1/assets").param("status", expected[0]).with(as(tenant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(expected[1]));
        }
        mvc.perform(get("/api/v1/assets").with(as(tenant))).andExpect(jsonPath("$.items", hasSize(3)));
        mvc.perform(get("/api/v1/assets").with(as(UUID.randomUUID()))).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void listIsPaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            createAsset();
        }

        var first = mvc.perform(get("/api/v1/assets").param("size", "2").with(as(tenant)))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        var second = mvc.perform(get("/api/v1/assets").param("size", "2").param("page", "1").with(as(tenant)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(first).doesNotContain((String) JsonPath.read(second, "$.items[0].id"));
        mvc.perform(get("/api/v1/assets").param("size", "201").with(as(tenant)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dismissalViewShowsWhatTheEmployeeStillHoldsAndItsCost() throws Exception {
        String kept = createAsset();
        String alsoKept = createAsset();
        String handedBack = createAsset();
        assign(kept, employee);
        assign(alsoKept, employee);
        assign(handedBack, employee);
        mvc.perform(delete("/api/v1/assets/{id}/assignment", handedBack).with(as(tenant)));

        mvc.perform(get("/api/v1/employees/{id}/assets", employee).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(employee.toString()))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalCost").value(160000.00));
        mvc.perform(get("/api/v1/employees/{id}/assets", employee).with(as(UUID.randomUUID())))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCost").value(0));
    }
}
