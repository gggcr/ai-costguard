package ai.costguard.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.costguard.gateway.support.FakeProvider;
import java.time.Clock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** The assessor's question in each provider's own API, and the answer read back. */
class ProviderTaskFitModelTest {

    static final FakeProvider API = start();

    static FakeProvider start() {
        try {
            return new FakeProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        API.close();
    }

    @BeforeEach
    void reset() {
        API.received.clear();
        API.status = 200;
    }

    private static ProviderTaskFitModel model(String provider) {
        var settings = new ProviderProperties.Provider(API.url(), provider, "key-1", "folder-1", null, null);
        return new ProviderTaskFitModel(provider, settings, "m-1", new ProviderCredentials(Clock.systemUTC()),
                WebClient.create());
    }

    private static String header(FakeProvider.Received r, String name) {
        return r.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
    }

    @Test
    void openAiStyle() {
        API.response = "{\"choices\":[{\"message\":{\"content\":\"ДА\"}}]}";

        assertThat(model("openai").ask("sys", "user").block()).isEqualTo("ДА");

        FakeProvider.Received r = API.received.getFirst();
        assertThat(r.path()).isEqualTo("/v1/chat/completions");
        assertThat(header(r, "Authorization")).isEqualTo("Bearer key-1");
        assertThat(r.body()).contains("\"model\":\"m-1\"").contains("\"role\":\"system\",\"content\":\"sys\"")
                .contains("\"max_tokens\":8");
    }

    @Test
    void anthropicStyle() {
        API.response = "{\"content\":[{\"type\":\"text\",\"text\":\"НЕТ\"}]}";

        assertThat(model("anthropic").ask("sys", "user").block()).isEqualTo("НЕТ");

        FakeProvider.Received r = API.received.getFirst();
        assertThat(r.path()).isEqualTo("/v1/messages");
        assertThat(header(r, "x-api-key")).isEqualTo("key-1");
        assertThat(r.body()).contains("\"system\":\"sys\"");
    }

    @Test
    void yandexStyle() {
        API.response = "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\"ДА\"}}]}}";

        assertThat(model("yandex").ask("sys", "user").block()).isEqualTo("ДА");

        FakeProvider.Received r = API.received.getFirst();
        assertThat(r.path()).isEqualTo("/foundationModels/v1/completion");
        assertThat(header(r, "Authorization")).isEqualTo("Api-Key key-1");
        assertThat(r.body()).contains("\"modelUri\":\"gpt://folder-1/m-1\"");
    }

    @Test
    void anAnswerWithoutTextOrAnErrorStatusIsAnError() {
        API.response = "{\"choices\":[]}";
        assertThatThrownBy(() -> model("openai").ask("sys", "user").block()).isInstanceOf(IllegalStateException.class);
        API.status = 500;
        assertThatThrownBy(() -> model("openai").ask("sys", "user").block()).isInstanceOf(RuntimeException.class);
    }
}
