package ai.costguard.core.shared.names;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** ADR 0073: the zone a company named, else its country's; Moscow for a company from before countries. */
class TimeZonesTest {

    @Test
    void aNamedZoneWins() {
        assertThat(TimeZones.of("America/Chicago", "US")).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(TimeZones.of(" Europe/Berlin ", null)).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void aCountryGivesItsMainZone() {
        assertThat(TimeZones.of(null, "US")).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(TimeZones.of("", "de")).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(TimeZones.of(null, "GB")).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(TimeZones.of(null, "RU")).isEqualTo(TimeZones.LEGACY);
        assertThat(TimeZones.of(null, "ZZ")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void aCompanyWithoutACountryKeepsMoscow() {
        assertThat(TimeZones.of(null, null)).isEqualTo(ZoneId.of("Europe/Moscow"));
    }

    @Test
    void onlyARegionNameIsAZone() {
        assertThat(TimeZones.parse("Mars/Olympus")).isEmpty();
        assertThat(TimeZones.parse("+03:00")).isEmpty();
        assertThat(TimeZones.parse("UTC")).isEmpty();
        assertThat(TimeZones.of("nonsense", "FR")).isEqualTo(ZoneId.of("Europe/Paris"));
    }
}
