package ai.costguard.core.plans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.plans.PlanEnrollment;
import ai.costguard.core.shared.security.RealmRoles;
import ai.costguard.platform.tenant.TenantSession;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stripe subscriptions end to end inside the core (ADR 0077), against a stand-in for Stripe's API on a local port
 * and webhooks signed the way Stripe signs them. No real Stripe account is involved: these tests prove the core's
 * side of the contract (what it sends, what it accepts and what it does), not Stripe's behaviour.
 */
@SpringBootTest(properties = {
        "costguard.plans.payments.provider=stripe",
        "costguard.plans.payments.stripe.secret-key=sk_test_stand_in",
        "costguard.plans.payments.stripe.webhook-secret=whsec_stand_in",
        "costguard.plans.payments.stripe.prices.start-usd=price_start_usd",
        "costguard.plans.payments.stripe.prices.business-usd=price_business_usd",
        "costguard.plans.payments.stripe.prices.start-eur=price_start_eur"})
@AutoConfigureMockMvc
class StripeBillingIntegrationTest extends PostgresTestSupport {

    private static final String SECRET = "whsec_stand_in";
    private static final List<Map<String, String>> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final HttpServer STRIPE = stripe();

    @DynamicPropertySource
    static void stripeUrl(DynamicPropertyRegistry registry) {
        registry.add("costguard.plans.payments.stripe.api-url",
                () -> "http://localhost:" + STRIPE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        STRIPE.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired PlanEnrollment enrollment;
    @Autowired TenantSession session;
    @Autowired TransactionTemplate transactions;

    private final UUID tenant = UUID.randomUUID();
    private final JdbcClient admin = JdbcClient.create(superuserAppDataSource());
    private final String sub = "sub_" + UUID.randomUUID().toString().replace("-", "");
    private final String customer = "cus_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);

    @BeforeEach
    void trial() {
        transactions.executeWithoutResult(tx -> {
            session.bind(tenant);
            enrollment.startTrial(tenant);
        });
    }

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", "owner@brightline.example")
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    private ResultActions checkout(String plan, String currency) throws Exception {
        return mvc.perform(post("/api/v1/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content(currency == null ? "{\"plan\":\"%s\"}".formatted(plan)
                        : "{\"plan\":\"%s\",\"currency\":\"%s\"}".formatted(plan, currency))
                .with(as("owner")));
    }

    /** Signed as Stripe signs: HMAC-SHA256 of "t.body" with the endpoint secret. */
    private ResultActions webhook(String body, long t, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(post("/api/v1/billing/notifications/stripe").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=" + t + ",v1=" + v1).content(body));
    }

    private ResultActions webhook(String body) throws Exception {
        return webhook(body, Instant.now().getEpochSecond(), SECRET);
    }

    private static String event(String id, String type, long created, String object) {
        return """
                {"id":"%s","object":"event","type":"%s","created":%d,"data":{"object":%s}}"""
                .formatted(id, type, created, object);
    }

    private String completed(String session, String eventId) {
        return event(eventId, "checkout.session.completed", Instant.now().getEpochSecond(), """
                {"id":"%s","object":"checkout.session","payment_status":"paid","subscription":"%s","customer":"%s"}"""
                .formatted(session, sub, customer));
    }

    private String invoice(String eventId, String type, String reason, Instant periodEnd, String price) {
        // the 2025 shape: the subscription under parent, the price under pricing
        return event(eventId, type, Instant.now().getEpochSecond(), """
                {"id":"in_%s","object":"invoice","billing_reason":"%s","customer":"%s","currency":"usd",
                 "amount_paid":14900,"amount_due":14900,
                 "parent":{"subscription_details":{"subscription":"%s"}},
                 "lines":{"data":[{"period":{"start":%d,"end":%d},
                                   "pricing":{"price_details":{"price":"%s"}}}]}}"""
                .formatted(eventId, reason, customer, sub, periodEnd.minus(30, ChronoUnit.DAYS).getEpochSecond(),
                        periodEnd.getEpochSecond(), price));
    }

    private String subscriptionEvent(String eventId, String type, long created, String status, String price,
            boolean cancel) {
        // the older shape: the period on the subscription itself
        return event(eventId, type, created, """
                {"id":"%s","object":"subscription","customer":"%s","status":"%s","cancel_at_period_end":%s,
                 "current_period_end":%d,"items":{"data":[{"price":{"id":"%s"}}]}}"""
                .formatted(sub, customer, status, cancel, Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond(),
                        price));
    }

    /** Pays for start in dollars through the stand-in and the signed webhook; returns the checkout session id. */
    private String subscribed() throws Exception {
        String body = checkout("start", "USD").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending")).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.confirmationUrl");
        String session = url.substring(url.lastIndexOf('/') + 1);
        webhook(completed(session, "evt_done_" + tenant)).andExpect(status().isOk());
        return session;
    }

    private Map<String, Object> plan() {
        return admin.sql("select plan, access_until, version from tenant_plan where tenant_id = ?").param(tenant)
                .query().singleRow();
    }

    private Instant accessUntil() {
        return ((Timestamp) plan().get("access_until")).toInstant();
    }

    @Test
    void aCheckoutStartsASubscriptionAtStripeAndWaitsForTheWebhook() throws Exception {
        int before = CALLS.size();
        String body = checkout("start", "USD").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.confirmationUrl").value(org.hamcrest.Matchers.startsWith(
                        "https://checkout.stripe.test/cs_test_")))
                .andExpect(jsonPath("$.plan.plan").value("trial"))
                .andReturn().getResponse().getContentAsString();

        Map<String, String> call = CALLS.get(before);
        assertThat(call.get("path")).isEqualTo("/v1/checkout/sessions");
        assertThat(call.get("authorization")).isEqualTo("Bearer sk_test_stand_in");
        assertThat(call.get("idempotency-key")).isEqualTo(JsonPath.read(body, "$.paymentId"));
        assertThat(call.get("mode")).isEqualTo("subscription");
        assertThat(call.get("line_items[0][price]")).isEqualTo("price_start_usd");
        assertThat(call.get("subscription_data[metadata][tenant_id]")).isEqualTo(tenant.toString());
        assertThat(body).doesNotContain("sk_test");
    }

    @Test
    void theSignedWebhookMakesThePlanPaidAndARepeatedDeliveryChangesNothing() throws Exception {
        String session = subscribed();
        assertThat(plan().get("plan")).isEqualTo("start");
        long version = (Long) plan().get("version");
        mvc.perform(get("/api/v1/billing/plan").with(as("owner")))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.subscription.provider").value("stripe"));

        webhook(completed(session, "evt_done_" + tenant)).andExpect(status().isOk());
        assertThat(plan().get("version")).isEqualTo(version);
    }

    @Test
    void anUnsignedForgedOrReplayedWebhookChangesNothing() throws Exception {
        String body = checkout("start", "USD").andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.confirmationUrl");
        String done = completed(url.substring(url.lastIndexOf('/') + 1), "evt_forged_" + tenant);

        mvc.perform(post("/api/v1/billing/notifications/stripe").contentType(MediaType.APPLICATION_JSON)
                .content(done)).andExpect(status().isBadRequest());
        webhook(done, Instant.now().getEpochSecond(), "whsec_someone_else").andExpect(status().isBadRequest());
        webhook(done, Instant.now().minusSeconds(3600).getEpochSecond(), SECRET).andExpect(status().isBadRequest());
        webhook(done.replace("\"paid\"", "\"paid\" "), Instant.now().getEpochSecond(), "x")
                .andExpect(status().isBadRequest());
        assertThat(plan().get("plan")).isEqualTo("trial");
    }

    @Test
    void aRenewalExtendsAccessToThePaidPeriodOnceAndShowsInTheHistory() throws Exception {
        subscribed();
        Instant end = accessUntil().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String renewal = invoice("evt_renew_" + tenant, "invoice.paid", "subscription_cycle", end, "price_start_usd");

        webhook(renewal).andExpect(status().isOk());
        webhook(renewal).andExpect(status().isOk());

        assertThat(accessUntil()).isEqualTo(end);
        mvc.perform(get("/api/v1/billing/payments").with(as("finance"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(149.00))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].status").value("succeeded"));
        mvc.perform(get("/api/v1/billing/payments").with(as("employee"))).andExpect(status().isForbidden());
    }

    @Test
    void anEventAboutASubscriptionNotRecordedYetIsAskedAgainAndThenApplied() throws Exception {
        Instant end = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String early = invoice("evt_early_" + tenant, "invoice.paid", "subscription_cycle", end, "price_start_usd");
        webhook(early).andExpect(status().isConflict());

        subscribed();
        webhook(early).andExpect(status().isOk());
        assertThat(accessUntil()).isEqualTo(end);
    }

    @Test
    void aChangeOfPlanInThePortalChangesThePlanAndAnOlderStatusDoesNotUndoIt() throws Exception {
        subscribed();
        long now = Instant.now().getEpochSecond();
        webhook(subscriptionEvent("evt_up_" + tenant, "customer.subscription.updated", now + 5, "active",
                "price_business_usd", false)).andExpect(status().isOk());
        assertThat(plan().get("plan")).isEqualTo("business");

        webhook(subscriptionEvent("evt_old_" + tenant, "customer.subscription.updated", now - 600, "incomplete",
                "price_business_usd", false)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/billing/plan").with(as("owner")))
                .andExpect(jsonPath("$.plan").value("business"))
                .andExpect(jsonPath("$.subscription.status").value("active"));
    }

    @Test
    void aFailedRenewalKeepsTheCompanyWorkingForTheGracePeriod() throws Exception {
        subscribed();
        Instant paid = accessUntil();
        webhook(invoice("evt_fail_" + tenant, "invoice.payment_failed", "subscription_cycle",
                paid.plus(30, ChronoUnit.DAYS), "price_start_usd")).andExpect(status().isOk());

        assertThat(accessUntil()).isEqualTo(paid.plus(7, ChronoUnit.DAYS));
        mvc.perform(get("/api/v1/billing/plan").with(as("owner")))
                .andExpect(jsonPath("$.subscription.status").value("past_due"));
    }

    @Test
    void aCancelledSubscriptionRunsToTheEndOfThePaidPeriodAndThenANewOneCanStart() throws Exception {
        subscribed();
        Instant paid = accessUntil();
        checkout("business", "USD").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("subscription_exists"));

        webhook(subscriptionEvent("evt_end_" + tenant, "customer.subscription.deleted",
                Instant.now().getEpochSecond() + 5, "canceled", "price_start_usd", false)).andExpect(status().isOk());
        assertThat(accessUntil()).isEqualTo(paid);
        mvc.perform(get("/api/v1/billing/plan").with(as("owner")))
                .andExpect(jsonPath("$.subscription.status").value("canceled"));

        int before = CALLS.size();
        checkout("business", "USD").andExpect(status().isOk());
        assertThat(CALLS.get(before).get("customer")).isEqualTo(customer);
    }

    @Test
    void onlyTheOwnerPaysAndOnlyInACurrencyThePlanIsSoldIn() throws Exception {
        checkout("start", "GBP").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("plan_currency_unknown"));
        checkout("business", "EUR").andExpect(status().isBadGateway());
        mvc.perform(post("/api/v1/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"start\",\"currency\":\"USD\"}").with(as("finance")))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePortalOpensForTheOwnerOfASubscription() throws Exception {
        mvc.perform(post("/api/v1/billing/portal").with(as("owner"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("subscription_missing"));
        subscribed();
        mvc.perform(post("/api/v1/billing/portal").with(as("finance"))).andExpect(status().isForbidden());
        int before = CALLS.size();
        mvc.perform(post("/api/v1/billing/portal").with(as("owner"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://billing.stripe.test/session"));
        assertThat(CALLS.get(before).get("customer")).isEqualTo(customer);
    }

    @Test
    void anEventTheCoreDoesNotUseIsAcknowledged() throws Exception {
        webhook(event("evt_misc_" + tenant, "customer.created", Instant.now().getEpochSecond(),
                "{\"id\":\"cus_x\",\"object\":\"customer\"}")).andExpect(status().isOk());
    }

    /** Records what the core sent; answers as Stripe does, with a session or a portal page. */
    private static HttpServer stripe() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> call = new java.util.HashMap<>();
                for (String pair : form.split("&")) {
                    String[] kv = pair.split("=", 2);
                    call.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                            kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
                }
                String path = exchange.getRequestURI().getPath();
                call.put("path", path);
                call.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (key != null) {
                    call.put("idempotency-key", key);
                }
                CALLS.add(call);
                String answer;
                int code = 200;
                if (path.equals("/v1/checkout/sessions") && "price_start_eur".equals(call.get("line_items[0][price]"))
                        || call.get("line_items[0][price]") == null && path.equals("/v1/checkout/sessions")) {
                    code = 400;
                    answer = "{\"error\":{\"code\":\"resource_missing\"}}";
                } else if (path.equals("/v1/checkout/sessions")) {
                    String id = "cs_test_" + SESSIONS.incrementAndGet() + "_" + UUID.randomUUID().toString().substring(0, 8);
                    answer = "{\"id\":\"%s\",\"url\":\"https://checkout.stripe.test/%s\"}".formatted(id, id);
                } else {
                    answer = "{\"url\":\"https://billing.stripe.test/session\"}";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
