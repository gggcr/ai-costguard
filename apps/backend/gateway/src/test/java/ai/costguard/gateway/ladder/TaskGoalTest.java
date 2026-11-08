package ai.costguard.gateway.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TaskGoalTest {

    @Test
    void aPercentEncodedGoalIsDecodedAsUtf8() {
        String header = URLEncoder.encode("Купить картриджи", StandardCharsets.UTF_8).replace("+", "%20");
        assertThat(TaskGoal.fromHeader(header)).isEqualTo("Купить картриджи");
    }

    @Test
    void plainAsciiAndAPlusStayAsTheyAre() {
        assertThat(TaskGoal.fromHeader("Buy 2+2 cartridges")).isEqualTo("Buy 2+2 cartridges");
    }

    @Test
    void aMalformedValueIsKept() {
        assertThat(TaskGoal.fromHeader("100% done")).isEqualTo("100% done");
    }

    @Test
    void blankMeansNoGoal() {
        assertThat(TaskGoal.fromHeader(null)).isNull();
        assertThat(TaskGoal.fromHeader("  ")).isNull();
        assertThat(TaskGoal.fromHeader("%20%20")).isNull();
    }

    @Test
    void aLongGoalIsCut() {
        assertThat(TaskGoal.fromHeader("a".repeat(5000))).hasSize(TaskGoal.MAX_LENGTH);
    }
}
