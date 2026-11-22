package ai.costguard.core.shared.names;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The time zone of a company (ADR 0073): what "today", "this month" and "every morning" mean for it. A company
 * names its own (IANA, such as America/Chicago); one that did not gets the main zone of its country, and a company
 * from before registration asked for a country keeps Moscow, as everything ran before.
 */
public final class TimeZones {

    /** The zone of a company that never said its country: every company before ADR 0036 was Russian. */
    public static final ZoneId LEGACY = ZoneId.of("Europe/Moscow");

    private static final Map<String, String> BY_COUNTRY = Map.ofEntries(
            Map.entry("RU", "Europe/Moscow"), Map.entry("BY", "Europe/Minsk"), Map.entry("KZ", "Asia/Almaty"),
            Map.entry("UA", "Europe/Kyiv"), Map.entry("AM", "Asia/Yerevan"), Map.entry("GE", "Asia/Tbilisi"),
            Map.entry("UZ", "Asia/Tashkent"), Map.entry("KG", "Asia/Bishkek"), Map.entry("AZ", "Asia/Baku"),
            Map.entry("US", "America/New_York"), Map.entry("CA", "America/Toronto"), Map.entry("MX", "America/Mexico_City"),
            Map.entry("BR", "America/Sao_Paulo"), Map.entry("AR", "America/Argentina/Buenos_Aires"),
            Map.entry("GB", "Europe/London"), Map.entry("IE", "Europe/Dublin"), Map.entry("PT", "Europe/Lisbon"),
            Map.entry("ES", "Europe/Madrid"), Map.entry("FR", "Europe/Paris"), Map.entry("BE", "Europe/Brussels"),
            Map.entry("NL", "Europe/Amsterdam"), Map.entry("LU", "Europe/Luxembourg"), Map.entry("DE", "Europe/Berlin"),
            Map.entry("CH", "Europe/Zurich"), Map.entry("AT", "Europe/Vienna"), Map.entry("IT", "Europe/Rome"),
            Map.entry("DK", "Europe/Copenhagen"), Map.entry("NO", "Europe/Oslo"), Map.entry("SE", "Europe/Stockholm"),
            Map.entry("FI", "Europe/Helsinki"), Map.entry("EE", "Europe/Tallinn"), Map.entry("LV", "Europe/Riga"),
            Map.entry("LT", "Europe/Vilnius"), Map.entry("PL", "Europe/Warsaw"), Map.entry("CZ", "Europe/Prague"),
            Map.entry("SK", "Europe/Bratislava"), Map.entry("HU", "Europe/Budapest"), Map.entry("SI", "Europe/Ljubljana"),
            Map.entry("HR", "Europe/Zagreb"), Map.entry("RS", "Europe/Belgrade"), Map.entry("RO", "Europe/Bucharest"),
            Map.entry("BG", "Europe/Sofia"), Map.entry("GR", "Europe/Athens"), Map.entry("CY", "Asia/Nicosia"),
            Map.entry("MT", "Europe/Malta"), Map.entry("TR", "Europe/Istanbul"), Map.entry("IL", "Asia/Jerusalem"),
            Map.entry("AE", "Asia/Dubai"), Map.entry("IN", "Asia/Kolkata"), Map.entry("SG", "Asia/Singapore"),
            Map.entry("HK", "Asia/Hong_Kong"), Map.entry("CN", "Asia/Shanghai"), Map.entry("JP", "Asia/Tokyo"),
            Map.entry("KR", "Asia/Seoul"), Map.entry("AU", "Australia/Sydney"), Map.entry("NZ", "Pacific/Auckland"));

    private TimeZones() {
    }

    /** An IANA zone id people may store, such as Europe/Berlin; empty for anything else, offsets included. */
    public static Optional<ZoneId> parse(String id) {
        if (id == null || id.isBlank() || !id.strip().contains("/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(id.strip()));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** The main zone of a country; UTC for a country not listed; Moscow for a company without one. */
    public static ZoneId defaultFor(String country) {
        if (country == null || country.isBlank()) {
            return LEGACY;
        }
        String zone = BY_COUNTRY.get(country.strip().toUpperCase(Locale.ROOT));
        return zone == null ? ZoneId.of("UTC") : ZoneId.of(zone);
    }

    /** The company's zone: the one it named, else its country's. */
    public static ZoneId of(String timeZone, String country) {
        return parse(timeZone).orElseGet(() -> defaultFor(country));
    }
}
