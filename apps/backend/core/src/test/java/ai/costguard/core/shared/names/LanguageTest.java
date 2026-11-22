package ai.costguard.core.shared.names;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR 0074: the language a company named, else its country's; Russian for a company from before countries. */
class LanguageTest {

    @Test
    void aNamedLanguageWins() {
        assertThat(Language.of("en", "RU")).isEqualTo(Language.EN);
        assertThat(Language.of(" RU ", "US")).isEqualTo(Language.RU);
    }

    @Test
    void aCountryGivesItsLanguage() {
        assertThat(Language.of(null, "US")).isEqualTo(Language.EN);
        assertThat(Language.of(null, "de")).isEqualTo(Language.EN);
        assertThat(Language.of(null, "KZ")).isEqualTo(Language.RU);
        assertThat(Language.of(null, null)).isEqualTo(Language.RU);
        assertThat(Language.of("fr", "FR")).isEqualTo(Language.EN);
    }

    @Test
    void picksTheTextOfItsLanguage() {
        assertThat(Language.EN.pick("Принята", "Accepted")).isEqualTo("Accepted");
        assertThat(Language.RU.pick("Принята", "Accepted")).isEqualTo("Принята");
        assertThat(Language.EN.code()).isEqualTo("en");
    }
}
