package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The specification in schemas/openapi is exported from the code. Regenerate it with
 * {@code ./gradlew :core:test --tests "*OpenApiContractTest" -Popenapi.update=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;

    @Test
    void committedSpecificationMatchesTheCode() throws Exception {
        String actual = mvc.perform(get("/v3/api-docs.yaml").with(jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Path committed = Path.of(System.getProperty("repo.root"), "schemas", "openapi", "core.yaml");

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(committed, actual);
        }

        assertThat(Files.readString(committed)).isEqualToNormalizingNewlines(actual);
    }
}
