package ai.costguard.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.agents.AgentRisks;
import ai.costguard.core.shared.security.RealmRoles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Risk signals of the detector reach the agent card and the people who answer for the agent, by e-mail and
 * Telegram (ADR 0031). Telegram is a fake Bot API on a local port; e-mail is caught before SMTP.
 */
@SpringBootTest(properties = {
        "costguard.notifications.mail-from=costguard@x.ru",
        "costguard.notifications.web-url=https://cg.example",
        "costguard.notifications.telegram.bot-token=test-token",
        "costguard.notifications.send-initial-delay=PT1H",
        "costguard.notifications.telegram.poll-initial-delay=PT1H",
        "costguard.notifications.max-attempts=3"})
@AutoConfigureMockMvc
@Import(RiskAndChannelsIntegrationTest.Mail.class)
class RiskAndChannelsIntegrationTest extends PostgresTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FakeTelegram TELEGRAM = new FakeTelegram();

    /** Telegram's side: updates waiting for getUpdates, messages sent, chats that fail. */
    static class FakeTelegram {
        final HttpServer server;
        final ConcurrentLinkedQueue<Map<String, Object>> updates = new ConcurrentLinkedQueue<>();
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        long nextUpdate = 100;

        FakeTelegram() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/bottest-token/", exchange -> {
                String method = exchange.getRequestURI().getPath().replace("/bottest-token/", "");
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                int status = 200;
                Object result;
                switch (method) {
                    case "getMe" -> result = Map.of("username", "costguard_test_bot");
                    case "getUpdates" -> {
                        List<Map<String, Object>> out = new java.util.ArrayList<>();
                        long offset = body.path("offset").asLong();
                        for (Map<String, Object> u : updates) {
                            if (((Number) u.get("update_id")).longValue() >= offset) {
                                out.add(u);
                            }
                        }
                        result = out;
                    }
                    case "sendMessage" -> {
                        long chat = body.path("chat_id").asLong();
                        status = chat == 403 ? 403 : chat == 500 ? 500 : 200;
                        if (status == 200) {
                            sent.add(body);
                        }
                        result = Map.of("message_id", 1);
                    }
                    default -> {
                        status = 404;
                        result = Map.of();
                    }
                }
                byte[] answer = JSON.writeValueAsBytes(status == 200 ? Map.of("ok", true, "result", result)
                        : Map.of("ok", false, "description", "fake " + status));
                exchange.sendResponseHeaders(status, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
        }

        void write(long chatId, String text) {
            updates.add(Map.of("update_id", nextUpdate++, "message",
                    Map.of("chat", Map.of("id", chatId), "text", text)));
        }

        List<JsonNode> to(long chatId) {
            return sent.stream().filter(m -> m.path("chat_id").asLong() == chatId).toList();
        }
    }

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("costguard.notifications.telegram.api-url",
                () -> "http://127.0.0.1:" + TELEGRAM.server.getAddress().getPort());
    }

    @TestConfiguration
    static class Mail {
        final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

        @Bean
        JavaMailSender javaMailSender() {
            return new JavaMailSenderImpl() {
                @Override
                public void send(SimpleMailMessage... messages) {
                    for (SimpleMailMessage m : messages) {
                        if (m.getTo() != null && m.getTo()[0].startsWith("broken")) {
                            throw new org.springframework.mail.MailSendException("fake SMTP down");
                        }
                        sent.add(m);
                    }
                }
            };
        }

        List<SimpleMailMessage> to(String address) {
            return sent.stream().filter(m -> address.equals(m.getTo()[0])).toList();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;
    @Autowired AgentRisks risks;
    @Autowired DeliverySender sender;
    @Autowired TelegramBot bot;
    @Autowired Mail mail;
    @Autowired DeliveryRepository deliveries;
    @Autowired NotificationRepository notifications;
    @Autowired ai.costguard.platform.tenant.TenantSession session;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private final UUID tenant = UUID.randomUUID();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String ownerEmail = "owner-" + suffix + "@x.ru";
    private final String auditorEmail = "auditor-" + suffix + "@x.ru";
    private long chat;

    @BeforeEach
    void chat() {
        chat = 10_000 + (long) (Math.random() * 1_000_000);
    }

    private RequestPostProcessor as(String email, String... roles) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString()).claim("email", email)
                .claim("realm_access", Map.of("roles", List.of(roles)))).authorities(realmRoles);
    }

    private RequestPostProcessor admin() {
        return as("admin-" + suffix + "@x.ru", "it_admin");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String read(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /** An agent whose owner is an employee with a CostGuard login, and an auditor who must hear nothing. */
    private UUID agentWithOwner() throws Exception {
        String department = JsonPath.read(read(json(post("/api/v1/departments"),
                "{\"name\":\"%s\"}".formatted(UUID.randomUUID())).with(admin())), "$.id");
        String employee = JsonPath.read(read(json(post("/api/v1/employees"), """
                {"name":"Owner","email":"%s","departmentId":"%s","position":"P"}
                """.formatted(ownerEmail, department)).with(admin())), "$.id");
        mvc.perform(json(post("/api/v1/users"), """
                {"name":"Owner","email":"%s","role":"agent_owner","employeeId":"%s"}
                """.formatted(ownerEmail, employee)).with(admin())).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/users"), """
                {"name":"Auditor","email":"%s","role":"auditor"}
                """.formatted(auditorEmail)).with(admin())).andExpect(status().isCreated());
        String mandate = JsonPath.read(read(json(post("/api/v1/mandates"), """
                {"name":"%s","models":["gpt-5"],"vendors":["OpenAI"],"maxPerOperation":500,"approvalThreshold":300,
                 "hours":"00:00–23:59"}""".formatted(UUID.randomUUID())).with(admin())), "$.id");
        String agent = JsonPath.read(read(json(post("/api/v1/agents"), """
                {"name":"Закупщик","description":"d","framework":"LangChain","departmentId":"%s","ownerId":"%s",
                 "mandateId":"%s"}""".formatted(department, employee, mandate)).with(admin())), "$.id");
        return UUID.fromString(agent);
    }

    private void linkTelegram(String email, long chatId) throws Exception {
        String url = JsonPath.read(read(post("/api/v1/notifications/telegram-link").with(as(email, "agent_owner"))),
                "$.url");
        assertThat(url).startsWith("https://t.me/costguard_test_bot?start=");
        TELEGRAM.write(chatId, "/start " + url.substring(url.indexOf("start=") + 6));
        bot.poll();
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Condition not met in 15 s");
            }
            Thread.sleep(100);
        }
    }

    private List<Notification> feed() {
        return new TransactionTemplate(transactions).execute(s -> {
            session.bind(tenant);
            return notifications.latest(50);
        });
    }

    private Notification awaitFeed(String fragment) throws InterruptedException {
        eventually(() -> feed().stream().anyMatch(n -> n.text().contains(fragment)));
        return feed().stream().filter(n -> n.text().contains(fragment)).findFirst().orElseThrow();
    }

    @Test
    void aStopReachesTheAgentCardAndItsOwnerByMailAndTelegram() throws Exception {
        UUID agent = agentWithOwner();
        linkTelegram(ownerEmail, chat);
        assertThat(TELEGRAM.to(chat)).extracting(m -> m.path("text").asText()).containsExactly(TelegramBot.LINKED);
        mvc.perform(get("/api/v1/notifications/settings").with(as(ownerEmail, "agent_owner")))
                .andExpect(jsonPath("$.telegramLinked").value(true))
                .andExpect(jsonPath("$.emailLevel").value("critical"));

        Instant t = Instant.parse("2026-11-10T10:00:00Z");
        risks.signal(tenant, agent, null, 92, "STOP", List.of("Расход в 8 раз выше обычного", "Ночью"), t);

        mvc.perform(get("/api/v1/agents/" + agent).with(admin()))
                .andExpect(jsonPath("$.riskScore").value(92))
                .andExpect(jsonPath("$.riskReason").value("Расход в 8 раз выше обычного; Ночью"));
        Notification stop = awaitFeed("остановил агента «Закупщик»");
        assertThat(stop.level()).isEqualTo(Level.critical);
        assertThat(stop.link()).isEqualTo("/agents/" + agent);

        sender.sendDue();
        assertThat(mail.to(ownerEmail)).hasSize(1);
        assertThat(mail.to(ownerEmail).getFirst().getText()).contains("https://cg.example/agents/" + agent);
        assertThat(mail.to(auditorEmail)).isEmpty();
        assertThat(TELEGRAM.to(chat)).hasSize(2);
        assertThat(TELEGRAM.to(chat).get(1).path("text").asText()).startsWith("🔴 ").contains("остановил");

        // An older signal arriving late changes nothing; the agent stays stopped
        risks.signal(tenant, agent, null, 5, "NORMAL", List.of(), t.minusSeconds(60));
        mvc.perform(get("/api/v1/agents/" + agent).with(admin())).andExpect(jsonPath("$.riskScore").value(92));

        // Calm again: a good notification, below the owner's levels for both channels
        risks.signal(tenant, agent, null, 0, "NORMAL", List.of("Последние 5 минут без отклонений"),
                t.plusSeconds(600));
        mvc.perform(get("/api/v1/agents/" + agent).with(admin())).andExpect(jsonPath("$.riskScore").value(0));
        assertThat(awaitFeed("снова работает без ограничений").level()).isEqualTo(Level.good);
        sender.sendDue();
        assertThat(mail.to(ownerEmail)).hasSize(1);
        assertThat(TELEGRAM.to(chat)).hasSize(2);
    }

    @Test
    void aTaskInALoopMarksItsAgentAndTheSameActionIsToldOnce() throws Exception {
        UUID agent = agentWithOwner();
        Instant t = Instant.parse("2026-11-10T11:00:00Z");
        risks.signal(tenant, agent, null, 20, "NORMAL", List.of(), t);
        risks.signal(tenant, agent, "task-7", 95, "STOP", List.of("Петля в задаче task-7"), t.plusSeconds(1));
        risks.signal(tenant, agent, "task-7", 97, "STOP", List.of("Петля в задаче task-7"), t.plusSeconds(2));

        mvc.perform(get("/api/v1/agents/" + agent).with(admin()))
                .andExpect(jsonPath("$.riskScore").value(97))
                .andExpect(jsonPath("$.riskReason").value("Петля в задаче task-7"));
        awaitFeed("задача task-7");
        Thread.sleep(500);
        assertThat(feed().stream().filter(n -> n.text().contains("задача task-7"))).hasSize(1);
    }

    @Test
    void levelsAreSavedAndTelegramIsUnlinked() throws Exception {
        agentWithOwner();
        mvc.perform(get("/api/v1/notifications/settings").with(as(ownerEmail, "agent_owner")))
                .andExpect(jsonPath("$.email").value(ownerEmail))
                .andExpect(jsonPath("$.emailAvailable").value(true))
                .andExpect(jsonPath("$.telegramAvailable").value(true))
                .andExpect(jsonPath("$.telegramLinked").value(false));
        mvc.perform(json(put("/api/v1/notifications/settings"), "{\"emailLevel\":\"info\",\"telegramLevel\":\"off\"}")
                        .with(as(ownerEmail, "agent_owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailLevel").value("info"))
                .andExpect(jsonPath("$.telegramLevel").value("off"));
        mvc.perform(json(put("/api/v1/notifications/settings"), "{\"emailLevel\":\"loud\",\"telegramLevel\":\"off\"}")
                .with(as(ownerEmail, "agent_owner"))).andExpect(status().isBadRequest());

        linkTelegram(ownerEmail, chat);
        mvc.perform(delete("/api/v1/notifications/telegram").with(as(ownerEmail, "agent_owner")))
                .andExpect(jsonPath("$.telegramLinked").value(false));

        // Someone without a CostGuard account in the company
        mvc.perform(get("/api/v1/notifications/settings").with(as("stranger@x.ru", "auditor")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("user_not_found"));
        // A used link does not work twice
        TELEGRAM.write(chat + 1, "/start nonsense");
        bot.poll();
        assertThat(TELEGRAM.to(chat + 1)).extracting(m -> m.path("text").asText()).containsExactly(TelegramBot.EXPIRED);
    }

    @Test
    void aFailingChannelIsRetriedAndABlockedBotUnlinks() throws Exception {
        UUID agent = agentWithOwner();
        mvc.perform(json(put("/api/v1/notifications/settings"), "{\"emailLevel\":\"off\",\"telegramLevel\":\"warning\"}")
                .with(as(ownerEmail, "agent_owner"))).andExpect(status().isOk());
        linkTelegram(ownerEmail, 500);
        risks.signal(tenant, agent, null, 75, "REQUIRE_APPROVAL", List.of("Новый поставщик"), Instant.now());
        Notification n = awaitFeed("требует согласования");

        sender.sendDue();
        List<DeliveryRepository.DeliveryState> states = new TransactionTemplate(transactions).execute(s -> {
            session.bind(tenant);
            return deliveries.states(n.id());
        });
        assertThat(states).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("pending");
            assertThat(d.attempts()).isEqualTo(1);
            assertThat(d.lastError()).contains("HTTP 500");
        });

        linkTelegram(ownerEmail, 403);
        risks.signal(tenant, agent, null, 95, "STOP", List.of("Петля"), Instant.now().plusSeconds(1));
        Notification stop = awaitFeed("остановил агента");
        sender.sendDue();
        List<DeliveryRepository.DeliveryState> stopped = new TransactionTemplate(transactions).execute(s -> {
            session.bind(tenant);
            return deliveries.states(stop.id());
        });
        assertThat(stopped).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("failed"));
        mvc.perform(get("/api/v1/notifications/settings").with(as(ownerEmail, "agent_owner")))
                .andExpect(jsonPath("$.telegramLinked").value(false));
    }

    @Test
    void thresholdsCompareLevels() {
        assertThat(Threshold.critical.allows(Level.critical)).isTrue();
        assertThat(Threshold.critical.allows(Level.warning)).isFalse();
        assertThat(Threshold.warning.allows(Level.critical)).isTrue();
        assertThat(Threshold.info.allows(Level.good)).isTrue();
        assertThat(Threshold.warning.allows(Level.good)).isFalse();
        assertThat(Threshold.off.allows(Level.critical)).isFalse();
    }
}
