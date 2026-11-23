package ai.costguard.core.privacy;

import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What every module holds about one person, for a request of access or portability (GDPR art. 15 and 20, CCPA
 * "right to know"; ADR 0079). Modules add the rows of their tables as they are stored, so the export is
 * machine-readable; secrets (PIN hashes, TOTP seeds, file contents) are left out by the modules' queries. Values
 * that JSON cannot carry as they are become text.
 */
public final class PersonalDataExport {

    private final Map<String, List<Map<String, Object>>> tables = new TreeMap<>();

    /** Adds rows of {@code module.table}; an empty list is kept too, so the export says the table was looked at. */
    public void rows(String table, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = tables.computeIfAbsent(table, t -> new ArrayList<>());
        for (Map<String, Object> row : rows) {
            Map<String, Object> clean = new LinkedHashMap<>();
            row.forEach((k, v) -> clean.put(k, value(v)));
            out.add(clean);
        }
    }

    public Map<String, List<Map<String, Object>>> tables() {
        return java.util.Collections.unmodifiableMap(tables);
    }

    private static Object value(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes, not exported>";
        }
        if (v instanceof Timestamp t) {
            return t.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof TemporalAccessor) {
            return v.toString();
        }
        if (v instanceof java.sql.Array a) {
            try {
                Object array = a.getArray();
                return array instanceof Object[] items
                        ? java.util.Arrays.asList(items).stream().map(PersonalDataExport::value).toList()
                        : String.valueOf(array);
            } catch (java.sql.SQLException e) {
                return "<array>";
            }
        }
        return v.toString();
    }
}
