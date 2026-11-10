package ai.costguard.core.software.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.shared.names.KnownServices;
import org.junit.jupiter.api.Test;

class ToolCategoriesTest {

    @Test
    void aSuiteDoesSeveralJobsAndTheVendorSpeaksWhenTheProductDoesNot() {
        assertThat(ToolCategories.of("Microsoft", "Microsoft 365 Business Standard"))
                .containsExactly("calls", "chat", "documents", "files", "mail");
        assertThat(ToolCategories.of("Zoom Video Communications", "Business")).containsExactly("calls");
        assertThat(ToolCategories.of("ООО «Рога и копыта»", "Учёт")).isEmpty();
    }

    @Test
    void theLongestNameWins() {
        assertThat(ToolCategories.of("GitHub", "GitHub Copilot Business")).containsExactly("assistant");
        assertThat(ToolCategories.of("GitHub", "GitHub Team")).containsExactly("code");
        assertThat(ToolCategories.of("Яндекс", "Яндекс Трекер")).containsExactly("tasks");
    }

    @Test
    void servicesAreKnownByAWordOfTheirName() {
        assertThat(KnownServices.saas("Notion Labs Inc")).isTrue();
        assertThat(KnownServices.saas("ООО Ромашка-Сервис")).isFalse();
        assertThat(KnownServices.aiProvider("OpenAI, Inc")).contains("openai");
        assertThat(KnownServices.aiProvider("CHATGPT SUBSCRIPTION")).contains("openai");
        assertThat(KnownServices.aiProvider("Оплата GigaChat API")).contains("sber");
        assertThat(KnownServices.aiProvider("Яндекс")).isEmpty();
        assertThat(KnownServices.aiProvider("Notion")).isEmpty();
    }
}
