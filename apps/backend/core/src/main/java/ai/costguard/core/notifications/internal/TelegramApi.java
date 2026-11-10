package ai.costguard.core.notifications.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Telegram Bot API over HTTPS. Off while {@code TELEGRAM_BOT_TOKEN} is empty. The token is part of every URL,
 * so URLs are never logged; errors name the method and the HTTP status only.
 */
@Component
class TelegramApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A message to the bot: the chat it came from and its text. */
    record Update(long id, long chatId, String text) {
    }

    /** {@code status} 0 when Telegram did not answer. */
    static class Failure extends RuntimeException {

        private final int status;

        Failure(String message, int status) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private final String base;
    private final String token;
    private final HttpClient http;
    private final AtomicReference<String> username = new AtomicReference<>();

    TelegramApi(@Value("${costguard.notifications.telegram.api-url:https://api.telegram.org}") String base,
            @Value("${costguard.notifications.telegram.bot-token:}") String token,
            @Value("${costguard.notifications.telegram.bot-username:}") String username) {
        this.base = base.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (username != null && !username.isBlank()) {
            this.username.set(username.trim().replaceFirst("^@", ""));
        }
    }

    boolean enabled() {
        return !token.isBlank();
    }

    private JsonNode call(String method, Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body))).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Failure("Telegram " + method + ": " + e.getClass().getSimpleName(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("Telegram " + method + ": interrupted", 0);
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new Failure("Telegram " + method + ": HTTP " + response.statusCode(), response.statusCode());
        }
        if (response.statusCode() != 200 || !answer.path("ok").asBoolean()) {
            throw new Failure("Telegram " + method + ": HTTP " + response.statusCode() + " "
                    + answer.path("description").asText(""), response.statusCode());
        }
        return answer.path("result");
    }

    private static String write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bot's name for t.me links: from the settings, else asked once. */
    String username() {
        String known = username.get();
        if (known == null) {
            known = call("getMe", Map.of()).path("username").asText();
            username.set(known);
        }
        return known;
    }

    List<Update> updates(long offset) {
        List<Update> out = new ArrayList<>();
        for (JsonNode u : call("getUpdates", Map.of("offset", offset, "timeout", 0,
                "allowed_updates", List.of("message")))) {
            JsonNode message = u.path("message");
            out.add(new Update(u.path("update_id").asLong(), message.path("chat").path("id").asLong(),
                    message.path("text").asText("")));
        }
        return out;
    }

    void send(long chatId, String text) {
        call("sendMessage", Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true));
    }
}
