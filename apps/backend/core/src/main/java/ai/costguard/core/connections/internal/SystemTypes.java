package ai.costguard.core.connections.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The systems the connector agent can pull (costguard_connectors.agent.SECTIONS) and the settings of each that
 * are not secrets. Anything else is refused, so a password or a token never lands in the core: secrets are in the
 * agent's environment, named in {@code secrets} for the page to show what the agent needs.
 */
final class SystemTypes {

    record SystemType(String type, String title, String kind, List<String> required, List<String> optional,
            List<String> secrets) {
    }

    static final Map<String, SystemType> ALL = new LinkedHashMap<>();

    private static void add(String type, String title, String kind, List<String> required, List<String> optional,
            List<String> secrets) {
        ALL.put(type, new SystemType(type, title, kind, required, optional, secrets));
    }

    static {
        add("onec", "1С (OData)", "accounting", List.of("url", "user"), List.of(), List.of("ONEC_PASSWORD"));
        // pay to «Мой кабинет», posted to the core over REST, not to Kafka (ADR 0054)
        add("onec_zup", "1С:ЗУП (зарплата)", "hr", List.of("url", "user"), List.of("days"),
                List.of("ONEC_ZUP_PASSWORD", "CORE_TOKEN"));
        add("moysklad", "МойСклад", "accounting", List.of(), List.of("url"), List.of("MOYSKLAD_TOKEN"));
        add("merge", "Merge (учёт и кадры)", "accounting", List.of(), List.of("url"),
                List.of("MERGE_API_KEY", "MERGE_ACCOUNTING_TOKEN", "MERGE_HRIS_TOKEN"));
        add("bitrix24", "Битрикс24", "hr", List.of(), List.of(), List.of("BITRIX24_WEBHOOK"));
        add("yandex360", "Яндекс 360", "identity", List.of("org_id"), List.of("url"), List.of("YANDEX360_TOKEN"));
        add("google_workspace", "Google Workspace", "identity", List.of("admin"), List.of("customer", "ignore"),
                List.of("GOOGLE_WORKSPACE_KEY"));
        add("okta", "Okta", "identity", List.of("url"), List.of("ignore"), List.of("OKTA_TOKEN"));
        add("entra", "Microsoft Entra ID", "identity", List.of("directory_id", "client_id"), List.of("ignore"),
                List.of("ENTRA_CLIENT_SECRET"));
        add("jamf", "Jamf Pro", "devices", List.of("url", "client_id"), List.of(), List.of("JAMF_CLIENT_SECRET"));
        add("intune", "Microsoft Intune", "devices", List.of("directory_id", "client_id"), List.of(),
                List.of("INTUNE_CLIENT_SECRET"));
        add("tbank", "Т-Банк", "bank", List.of(), List.of("accounts", "url"), List.of("TBANK_TOKEN"));
        add("mail", "Почтовый ящик со счетами", "mail", List.of("host", "user"),
                List.of("folder", "min_image_kb", "min_image_side"), List.of("MAIL_PASSWORD", "CORE_TOKEN"));
    }

    private SystemTypes() {
    }

    static Optional<SystemType> of(String type) {
        return Optional.ofNullable(ALL.get(type));
    }
}
