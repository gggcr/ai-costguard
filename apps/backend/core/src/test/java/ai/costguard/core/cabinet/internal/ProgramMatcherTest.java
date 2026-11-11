package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.software.LicensePool;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Сверка покупки с подписками (ADR 0062): what people call programs, typos, the model as the last word. */
class ProgramMatcherTest {

    private static LicensePool.Offer offer(String product, String vendor) {
        return new LicensePool.Offer(UUID.randomUUID(), vendor, product, 10, 5, 5, 0);
    }

    private static final LicensePool.Offer OPENAI = offer("ChatGPT Team", "OpenAI");
    private static final LicensePool.Offer M365 = offer("Microsoft 365 Business Standard", "Microsoft");
    private static final LicensePool.Offer ADOBE = offer("Creative Cloud All Apps", "Adobe");
    private static final LicensePool.Offer FIGMA = offer("Figma Professional", "Figma");

    /** A pool whose exact match finds nothing, as with the names below. */
    private static LicensePool pool(List<LicensePool.Offer> offers) {
        return new LicensePool() {
            public List<Held> heldBy(UUID employeeId) { return List.of(); }
            public List<Offer> offers(int idleDays) { return offers; }
            public Optional<Offer> offer(UUID subscriptionId, int idleDays) { return Optional.empty(); }
            public Optional<UUID> grant(UUID subscriptionId, UUID employeeId) { return Optional.empty(); }
            public Optional<Idle> idlest(UUID subscriptionId, int idleDays, UUID except) { return Optional.empty(); }
            public List<Idle> idle(int idleDays) { return List.of(); }
            public boolean reclaim(UUID licenseId) { return false; }
            public Optional<Offer> match(String text, int idleDays) { return Optional.empty(); }
            public Map<UUID, BigDecimal> monthlySeatPrices() { return Map.of(); }
        };
    }

    private final LicensePool pool = pool(List.of(OPENAI, M365, ADOBE, FIGMA));
    private final ProgramMatcher matcher = new ProgramMatcher("http://localhost:1", "", "openai", "");

    private Optional<LicensePool.Offer> find(String product) {
        return matcher.match(pool, 30, product, null).map(ProgramMatcher.Match::offer);
    }

    @Test
    void whatPeopleCallPrograms() {
        assertThat(find("Подписка Photoshop")).contains(ADOBE);
        assertThat(find("MS Teams")).contains(M365);
        assertThat(find("Excel")).contains(M365);
        assertThat(find("Фигма")).contains(FIGMA);
        assertThat(find("чат гпт плюс")).contains(OPENAI);
    }

    @Test
    void aTypoIsStillTheProgram() {
        assertThat(find("Fgima Professional")).contains(FIGMA);
        assertThat(ProgramMatcher.same("notion", "notoin")).isFalse(); // two edits in six letters
        assertThat(ProgramMatcher.same("zoom", "room")).isFalse();      // too short to guess
    }

    @Test
    void somethingElseIsNotMatched() {
        assertThat(find("Midjourney")).isEmpty();
        assertThat(find("WordPress хостинг")).isEmpty();
    }

    @Test
    void theModelPicksOnlyAmongTheSubscriptions() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>("2");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/providers/openai/v1/chat/completions", exchange -> {
            asked.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"" + answer.get() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProgramMatcher withModel = new ProgramMatcher("http://127.0.0.1:" + server.getAddress().getPort(),
                    "cg-key", "openai", "gpt-4o-mini");
            assertThat(withModel.match(pool, 30, "Outlook Calendar Pro для команды", "Contoso")
                    .map(ProgramMatcher.Match::how)).contains("alias");
            assertThat(withModel.match(pool, 30, "Дизайн-пакет для баннеров", null)
                    .map(ProgramMatcher.Match::offer)).contains(M365);
            assertThat(asked.get()).contains("Figma Professional").contains("Дизайн-пакет");
            answer.set("0");
            assertThat(withModel.match(pool, 30, "Что-то новое", null)).isEmpty();
            answer.set("17");
            assertThat(withModel.match(pool, 30, "Что-то новое", null)).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
