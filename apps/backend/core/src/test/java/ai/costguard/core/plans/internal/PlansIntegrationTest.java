package ai.costguard.core.plans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.plans.PlanAccessEnded;
import ai.costguard.core.plans.PlanAccessEnding;
import ai.costguard.core.plans.PlanEnrollment;
import ai.costguard.core.plans.TenantPlanChanged;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.platform.tenant.TenantSession;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pricing plans through the API (ADR 0037, ADR 0038): the limits in the modules that create agents, employees and
 * connections, read-only access after the trial, the watcher, and payment with a provider that stands in for a real
 * one. The trial allows one of each here.
 */
@SpringBootTest(properties = {
        "costguard.plans.catalog.trial.limits.agents=1",
        "costguard.plans.catalog.trial.limits.employees=1",
        "costguard.plans.catalog.trial.limits.connected-systems=1",
        "costguard.plans.payments.provider=test"})
@AutoConfigureMockMvc
@Import(PlansIntegrationTest.Fakes.class)
class PlansIntegrationTest extends PostgresTestSupport {

    /**
     * A provider whose payments wait for a notice, succeed at once, or fail (next null); a notice is genuine when
     * signed "ok".
     */
    static class TestProvider implements PaymentProvider {
        volatile Status next = Status.SUCCEEDED;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public Checkout start(PaymentRequest request) {
            if (next == null) {
                throw new IllegalStateException("provider down");
            }
            return new Checkout("tp-" + request.paymentId(), next,
                    next == Status.PENDING ? "https://pay.example/" + request.paymentId() : null);
        }

        @Override
        public Optional<Notice> notice(Map<String, String> headers, String body) {
            if (!"ok".equals(headers.get("x-signature"))) {
                return Optional.empty();
            }
            String[] parts = body.split(":");
            return Optional.of(new Notice(parts[1], Status.valueOf(parts[0])));
        }
    }

    @TestConfiguration
    static class Fakes {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @Bean
        TestProvider testProvider() {
            return new TestProvider();
        }

        @EventListener
        void on(TenantPlanChanged e) {
            events.add(e);
        }

        @EventListener
        void on(PlanAccessEnding e) {
            events.add(e);
        }

        @EventListener
        void on(PlanAccessEnded e) {
            events.add(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired PlanEnrollment enrollment;
    @Autowired PlanService plans;
    @Autowired PlansPersonalData holder;
    @Autowired TenantSession session;
    @Autowired TransactionTemplate transactions;
    @Autowired TestProvider provider;
    @Autowired Fakes fakes;

    private final UUID tenant = UUID.randomUUID();
    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "boss@acme.example")
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(realmRoles);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void trial() {
        transactions.executeWithoutResult(tx -> {
            session.bind(tenant);
            enrollment.startTrial(tenant);
        });
    }

    private void periodEnds(Instant at) {
        admin.sql("update tenant_plan set access_until = ? where tenant_id = ?").params(Timestamp.from(at), tenant)
                .update();
        admin.sql("update plan_schedule set access_until = ? where tenant_id = ?").params(Timestamp.from(at), tenant)
                .update();
    }

    private <T> List<T> events(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Object e : fakes.events) {
            if (type.isInstance(e)) {
                T t = type.cast(e);
                UUID of = switch (t) {
                    case TenantPlanChanged c -> c.tenantId();
                    case PlanAccessEnding w -> w.tenantId();
                    case PlanAccessEnded d -> d.tenantId();
                    default -> null;
                };
                if (tenant.equals(of)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private String department() throws Exception {
        return JsonPath.read(mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"%s\"}"
                .formatted(UUID.randomUUID())).with(as("it_admin"))).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.id");
    }

    private ResultActions employee(String department) throws Exception {
        return mvc.perform(json(post("/api/v1/employees"), """
                {"name":"Anna","email":"%s@x.ru","departmentId":"%s","position":"Engineer"}
                """.formatted(UUID.randomUUID(), department)).with(as("it_admin")));
    }

    @Test
    void theCatalogAndTheCompanysPlan() throws Exception {
        mvc.perform(get("/api/v1/billing/plans").with(as("auditor"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("trial"))
                .andExpect(jsonPath("$[1].name").value("start"))
                .andExpect(jsonPath("$[2].name").value("business"))
                .andExpect(jsonPath("$[0].limits.agents").value(1))
                .andExpect(jsonPath("$[1].monthlyPrice").value(4990));
        mvc.perform(get("/api/v1/billing/plan").with(as("auditor"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("plan_not_assigned"));

        trial();

        mvc.perform(get("/api/v1/billing/plan").with(as("auditor"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("trial")).andExpect(jsonPath("$.status").value("trial"))
                .andExpect(jsonPath("$.limits.employees").value(1));
        assertThat(events(TenantPlanChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.plan()).isEqualTo("trial");
            assertThat(e.readOnly()).isFalse();
            assertThat(e.operationsPerMonth()).isEqualTo(10000L);
        });
    }

    @Test
    void employeesStopAtTheLimitWithAClearMessageAndADismissalFreesAPlace() throws Exception {
        trial();
        String department = department();
        String first = JsonPath.read(employee(department).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id");

        employee(department).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("plan_limit_exceeded"))
                .andExpect(jsonPath("$.limit").value("employees"))
                .andExpect(jsonPath("$.plan").value("trial"))
                .andExpect(jsonPath("$.allowed").value(1))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.detail").value("The Trial plan allows 1 employee and the company has 1. "
                        + "Move to a bigger plan or remove some to add more."));

        mvc.perform(put("/api/v1/employees/" + first + "/dismissal").with(as("it_admin"))).andExpect(status().isOk());
        employee(department).andExpect(status().isCreated());
    }

    @Test
    void agentsStopAtTheLimit() throws Exception {
        trial();
        String mandate = JsonPath.read(mvc.perform(json(post("/api/v1/mandates"), """
                {"name":"%s","models":["gpt-5"],"vendors":["OpenAI"],"maxPerOperation":500,"approvalThreshold":300,
                 "hours":"круглосуточно"}
                """.formatted(UUID.randomUUID())).with(as("it_admin"))).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.id");
        String agent = """
                {"name":"Bot","description":"d","framework":"LangChain","departmentId":"%s","ownerId":"%s",
                 "mandateId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), mandate);

        mvc.perform(json(post("/api/v1/agents"), agent).with(as("it_admin"))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/agents"), agent).with(as("it_admin"))).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("plan_limit_exceeded"))
                .andExpect(jsonPath("$.limit").value("agents"));
    }

    @Test
    void connectedSystemsStopAtTheLimitAndADisconnectedOneFreesAPlace() throws Exception {
        trial();
        String okta = JsonPath.read(mvc.perform(json(post("/api/v1/connections"),
                        "{\"type\":\"okta\",\"name\":\"Okta\",\"settings\":{\"url\":\"https://acme.okta.example\"}}")
                .with(as("it_admin"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");
        String onec = "{\"type\":\"onec\",\"name\":\"1С\",\"settings\":{\"url\":\"http://1c.local/odata\","
                + "\"user\":\"costguard\"}}";

        mvc.perform(json(post("/api/v1/connections"), onec).with(as("it_admin")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.limit").value("connected_systems"));

        mvc.perform(delete("/api/v1/connections/" + okta).with(as("it_admin"))).andExpect(status().is2xxSuccessful());
        mvc.perform(json(post("/api/v1/connections"), onec).with(as("it_admin"))).andExpect(status().isCreated());
    }

    @Test
    void aCompanyFromBeforePlansIsNotLimited() throws Exception {
        String department = department();

        employee(department).andExpect(status().isCreated());
        employee(department).andExpect(status().isCreated());
    }

    @Test
    void afterTheTrialTheCompanyOnlyReadsButCanStillPay() throws Exception {
        trial();
        String department = department();
        periodEnds(Instant.now().minusSeconds(60));

        mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"x\"}").with(as("it_admin")))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("plan_read_only"));
        employee(department).andExpect(status().isPaymentRequired());
        mvc.perform(get("/api/v1/departments").with(as("it_admin"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/billing/plan").with(as("it_admin"))).andExpect(jsonPath("$.status").value("read_only"));

        provider.next = PaymentProvider.Status.SUCCEEDED;
        mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"start\"}").with(as("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan.status").value("active"));
        mvc.perform(json(post("/api/v1/departments"), "{\"name\":\"y\"}").with(as("it_admin")))
                .andExpect(status().isCreated());
    }

    @Test
    void theWatcherWarnsOnceThenEndsTheTrialOnce() {
        trial();
        periodEnds(Instant.now().plusSeconds(24 * 3600));

        assertThat(plans.due()).contains(tenant);
        plans.watch(tenant);
        plans.watch(tenant);
        assertThat(events(PlanAccessEnding.class)).singleElement().satisfies(e -> assertThat(e.trial()).isTrue());
        assertThat(plans.due()).doesNotContain(tenant);

        periodEnds(Instant.now().minusSeconds(1));
        plans.watch(tenant);
        plans.watch(tenant);
        assertThat(events(PlanAccessEnded.class)).singleElement().satisfies(e -> assertThat(e.trial()).isTrue());
        assertThat(events(TenantPlanChanged.class)).last().satisfies(e -> {
            assertThat(e.readOnly()).isTrue();
            assertThat(e.version()).isEqualTo(2);
        });
        assertThat(plans.due()).doesNotContain(tenant);
    }

    @Test
    void aPaymentThatSucceedsAtOnceChangesThePlanForAMonth() throws Exception {
        trial();
        provider.next = PaymentProvider.Status.SUCCEEDED;

        String body = mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"business\"}").with(as("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.confirmationUrl").doesNotExist())
                .andExpect(jsonPath("$.plan.plan").value("business"))
                .andReturn().getResponse().getContentAsString();

        Instant until = Instant.parse(JsonPath.read(body, "$.plan.accessUntil"));
        Instant month = OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1).toInstant();
        assertThat(until).isBetween(month.minusSeconds(60), month.plusSeconds(60));
        assertThat(admin.sql("select status from plan_payment where tenant_id = ?").param(tenant).query(String.class)
                .single()).isEqualTo("succeeded");
        assertThat(events(TenantPlanChanged.class)).last().satisfies(e -> {
            assertThat(e.plan()).isEqualTo("business");
            assertThat(e.operationsPerMonth()).isEqualTo(1_000_000L);
        });
    }

    @Test
    void aPendingPaymentChangesThePlanOnlyOnAVerifiedNoticeAndOnlyOnce() throws Exception {
        trial();
        provider.next = PaymentProvider.Status.PENDING;
        String body = mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"start\"}").with(as("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.plan.plan").value("trial")).andReturn().getResponse().getContentAsString();
        String payment = JsonPath.read(body, "$.paymentId");
        assertThat((String) JsonPath.read(body, "$.confirmationUrl")).isEqualTo("https://pay.example/" + payment);

        mvc.perform(post("/api/v1/billing/notifications/test").header("X-Signature", "forged")
                        .content("SUCCEEDED:tp-" + payment))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("payment_notice_invalid"));
        mvc.perform(get("/api/v1/billing/plan").with(as("owner"))).andExpect(jsonPath("$.plan").value("trial"));

        mvc.perform(post("/api/v1/billing/notifications/test").header("X-Signature", "ok")
                .content("SUCCEEDED:tp-" + payment)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/billing/notifications/test").header("X-Signature", "ok")
                .content("SUCCEEDED:tp-" + payment)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/billing/plan").with(as("owner"))).andExpect(jsonPath("$.plan").value("start"));
        assertThat(events(TenantPlanChanged.class)).hasSize(2);
        mvc.perform(post("/api/v1/billing/notifications/yookassa").content("x"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("payment_provider_unknown"));
    }

    @Test
    void aProviderThatFailsLeavesNoPendingPaymentAndThePlanAsItWas() throws Exception {
        trial();
        provider.next = null;
        try {
            mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"start\"}").with(as("owner")))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("payment_provider_failed"));
        } finally {
            provider.next = PaymentProvider.Status.SUCCEEDED;
        }

        assertThat(admin.sql("select status from plan_payment where tenant_id = ?").param(tenant).query(String.class)
                .single()).isEqualTo("canceled");
        mvc.perform(get("/api/v1/billing/plan").with(as("owner"))).andExpect(jsonPath("$.plan").value("trial"));
    }

    @Test
    void onlyAnOwnerPaysAndOnlyForAPaidPlan() throws Exception {
        trial();

        mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"start\"}").with(as("finance")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"trial\"}").with(as("owner")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("plan_unknown"));
    }

    @Test
    void anErasedCompanyTakesItsPlanAndPaymentsAlong() throws Exception {
        trial();
        provider.next = PaymentProvider.Status.SUCCEEDED;
        mvc.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"start\"}").with(as("owner")))
                .andExpect(status().isOk());
        List<String> tombstones = new ArrayList<>();
        Report report = new Report() {
            public void deleted(String store, long rows) {
            }

            public void anonymized(String store, long rows) {
            }

            public void retained(String store, long rows, String why, LocalDate until) {
            }

            public void manual(String store, String what) {
            }

            public void tombstone(String topic, String key) {
                tombstones.add(topic + "/" + key);
            }
        };

        transactions.executeWithoutResult(tx -> {
            session.bind(tenant);
            holder.eraseCompany(tenant, report);
        });

        for (String table : holder.tables()) {
            assertThat(admin.sql("select count(*) from " + table + " where tenant_id = ?").param(tenant)
                    .query(Long.class).single()).as(table).isZero();
        }
        assertThat(tombstones).containsExactly("tenants.plans.v1/" + tenant);
    }
}
