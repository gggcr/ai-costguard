package ai.costguard.core.software.internal;

import ai.costguard.core.shared.names.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a tool is for, from its product or vendor name: the rule {@code subscription.overlap} looks for two tools
 * that do the same job. A suite does several jobs (Microsoft 365 is mail, documents, calls, chat and files).
 * The longest name wins: "GitHub Copilot" is an assistant, not a code host like "GitHub".
 */
final class ToolCategories {

    private static final Map<String, Set<String>> BY_NAME = Map.ofEntries(
            Map.entry("microsoft 365", Set.of("mail", "documents", "calls", "chat", "files")),
            Map.entry("office 365", Set.of("mail", "documents", "calls", "chat", "files")),
            Map.entry("google workspace", Set.of("mail", "documents", "calls", "files")),
            Map.entry("g suite", Set.of("mail", "documents", "calls", "files")),
            Map.entry("яндекс 360", Set.of("mail", "documents", "calls", "files")),
            Map.entry("yandex 360", Set.of("mail", "documents", "calls", "files")),
            Map.entry("vk workspace", Set.of("mail", "documents", "calls", "files")),
            Map.entry("мойофис", Set.of("mail", "documents")),
            Map.entry("myoffice", Set.of("mail", "documents")),
            Map.entry("zoom", Set.of("calls")),
            Map.entry("телемост", Set.of("calls")),
            Map.entry("google meet", Set.of("calls")),
            Map.entry("контур толк", Set.of("calls")),
            Map.entry("trueconf", Set.of("calls")),
            Map.entry("webex", Set.of("calls")),
            Map.entry("microsoft teams", Set.of("calls", "chat")),
            Map.entry("slack", Set.of("chat")),
            Map.entry("mattermost", Set.of("chat")),
            Map.entry("пачка", Set.of("chat")),
            Map.entry("pachca", Set.of("chat")),
            Map.entry("rocket chat", Set.of("chat")),
            Map.entry("jira", Set.of("tasks")),
            Map.entry("youtrack", Set.of("tasks")),
            Map.entry("asana", Set.of("tasks")),
            Map.entry("trello", Set.of("tasks")),
            Map.entry("monday", Set.of("tasks")),
            Map.entry("clickup", Set.of("tasks")),
            Map.entry("kaiten", Set.of("tasks")),
            Map.entry("яндекс трекер", Set.of("tasks")),
            Map.entry("weeek", Set.of("tasks")),
            Map.entry("планфикс", Set.of("tasks")),
            Map.entry("битрикс24", Set.of("crm", "tasks")),
            Map.entry("bitrix24", Set.of("crm", "tasks")),
            Map.entry("amocrm", Set.of("crm")),
            Map.entry("retailcrm", Set.of("crm")),
            Map.entry("мегаплан", Set.of("crm", "tasks")),
            Map.entry("salesforce", Set.of("crm")),
            Map.entry("hubspot", Set.of("crm")),
            Map.entry("pipedrive", Set.of("crm")),
            Map.entry("confluence", Set.of("wiki")),
            Map.entry("notion", Set.of("wiki")),
            Map.entry("yonote", Set.of("wiki")),
            Map.entry("яндекс вики", Set.of("wiki")),
            Map.entry("miro", Set.of("whiteboard")),
            Map.entry("mural", Set.of("whiteboard")),
            Map.entry("figjam", Set.of("whiteboard")),
            Map.entry("figma", Set.of("design")),
            Map.entry("sketch", Set.of("design")),
            Map.entry("pixso", Set.of("design")),
            Map.entry("dropbox", Set.of("files")),
            Map.entry("google drive", Set.of("files")),
            Map.entry("яндекс диск", Set.of("files")),
            Map.entry("onedrive", Set.of("files")),
            Map.entry("github", Set.of("code")),
            Map.entry("gitlab", Set.of("code")),
            Map.entry("bitbucket", Set.of("code")),
            Map.entry("github copilot", Set.of("assistant")),
            Map.entry("chatgpt", Set.of("assistant")),
            Map.entry("claude", Set.of("assistant")),
            Map.entry("gigachat", Set.of("assistant")),
            Map.entry("yandexgpt", Set.of("assistant")),
            Map.entry("perplexity", Set.of("assistant")),
            Map.entry("gemini", Set.of("assistant")),
            Map.entry("1password", Set.of("passwords")),
            Map.entry("lastpass", Set.of("passwords")),
            Map.entry("bitwarden", Set.of("passwords")),
            Map.entry("пассворк", Set.of("passwords")),
            Map.entry("passwork", Set.of("passwords")));

    private ToolCategories() {
    }

    /** The jobs of the product; when its name says nothing, the jobs of the vendor; empty when unknown. */
    static Set<String> of(String vendor, String product) {
        Set<String> byProduct = in(Names.clean(product));
        return byProduct.isEmpty() ? in(Names.clean(vendor)) : byProduct;
    }

    private static Set<String> in(String cleaned) {
        String text = " " + cleaned + " ";
        List<String> matched = new ArrayList<>();
        for (String name : BY_NAME.keySet()) {
            if (Pattern.compile("(?U)(?<![\\w])" + Pattern.quote(name) + "(?![\\w])").matcher(text).find()) {
                matched.add(name);
            }
        }
        Set<String> jobs = new TreeSet<>();
        for (String name : matched) {
            boolean inLonger = matched.stream().anyMatch(other -> other.length() > name.length()
                    && other.contains(name));
            if (!inLonger) {
                jobs.addAll(BY_NAME.get(name));
            }
        }
        return jobs;
    }
}
