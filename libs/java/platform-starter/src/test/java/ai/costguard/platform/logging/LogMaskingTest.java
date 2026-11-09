package ai.costguard.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LogMaskingTest {

    /** The cases the Python masker runs too, so both sides mask the same way. */
    static Stream<Map<String, String>> cases() throws IOException {
        String json = Files.readString(Path.of(System.getProperty("repo.root"), "schemas", "logging",
                "masking-cases.json"));
        List<Map<String, String>> cases = JsonPath.read(json, "$.cases");
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void theSharedCases(Map<String, String> c) {
        assertThat(LogMasking.mask(c.get("in"))).isEqualTo(c.get("out"));
    }

    @Test
    void theLayoutMasksTheArgumentsAndTheStackTrace() {
        LoggerContext context = new LoggerContext();
        MaskingPatternLayout layout = new MaskingPatternLayout();
        layout.setContext(context);
        layout.setPattern("%m%n%ex");
        layout.start();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.WARN, "Employee {} skipped",
                new IllegalStateException("duplicate key ivan@romashka.ru"), new Object[] {"anna@romashka.ru"});

        String line = layout.doLayout(event);

        assertThat(line).contains("Employee ***@romashka.ru skipped", "duplicate key ***@romashka.ru")
                .doesNotContain("ivan@", "anna@");
    }
}
