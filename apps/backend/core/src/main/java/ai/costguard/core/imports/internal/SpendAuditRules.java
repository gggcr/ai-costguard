package ai.costguard.core.imports.internal;

import ai.costguard.core.agents.GatewayVendors;
import ai.costguard.core.catalog.ExchangeRates;
import ai.costguard.core.findings.AuditRules;
import ai.costguard.core.shared.names.KnownServices;
import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.SoftwareInventory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rules over the invoices and payments the pipeline and the connectors reported (ingest_entity), for the last
 * {@code costguard.audit.spend-days} (90) days. Only entities tied to a vendor count. Amounts are converted to
 * roubles at the Bank of Russia rate of their day; one without a rate is left out of the sums.
 *
 * <ul>
 *   <li>{@code invoice.duplicate}: a vendor's invoice that came twice: the same number, or with no number the same
 *       total on the same day. The later one is the finding, and the saving is its total, once.
 *   <li>{@code invoice.overcharge}: a vendor's invoice above what its subscriptions cost in their period by more
 *       than {@code costguard.audit.invoice-tolerance} (2%). An invoice for a whole number of periods is a
 *       prepayment, not an error. A vendor whose subscriptions are paid both monthly and yearly is not checked:
 *       nothing says which of them an invoice is for.
 *   <li>{@code vendor.shadow}: payments to a SaaS (a known service, or a software merchant code) with no
 *       subscription or contract in the registry. The saving is the average month, if nobody needs it.
 *   <li>{@code ai.bypass}: payments to an AI provider past the gateway: by card, or to a provider no published
 *       mandate names. The gateway's own provider account is paid by transfer, and its spend is in the ledger.
 *       The saving is not known: the finding is about control, so it is zero.
 * </ul>
 * ADR 0032 has the reasons.
 */
@Component
class SpendAuditRules implements AuditRules {

    static final String DUPLICATE = "invoice.duplicate";
    static final String OVERCHARGE = "invoice.overcharge";
    static final String SHADOW = "vendor.shadow";
    static final String AI_BYPASS = "ai.bypass";

    /** Merchant codes of software: telecom and information services, software, digital goods, data processing. */
    static final Set<String> SOFTWARE_MCC = Set.of("4816", "5734", "5817", "5818", "7372");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private final JdbcClient jdbc;
    private final SoftwareInventory software;
    private final GatewayVendors gateway;
    private final ExchangeRates rates;
    private final int days;
    private final BigDecimal tolerance;

    SpendAuditRules(JdbcClient jdbc, SoftwareInventory software, GatewayVendors gateway, ExchangeRates rates,
            @Value("${costguard.audit.spend-days:90}") int days,
            @Value("${costguard.audit.invoice-tolerance:0.02}") BigDecimal tolerance) {
        this.jdbc = jdbc;
        this.software = software;
        this.gateway = gateway;
        this.rates = rates;
        this.days = days;
        this.tolerance = tolerance;
    }

    @Override
    public Set<String> rules() {
        return Set.of(DUPLICATE, OVERCHARGE, SHADOW, AI_BYPASS);
    }

    /** An invoice or a payment as the source saw it; {@code rub} is null when the currency has no rate. */
    record Spend(String key, String type, UUID vendorId, BigDecimal amount, String currency, BigDecimal rub,
            LocalDate day, boolean dated, String number, String purpose, boolean card, String mcc, String role,
            String source, Timestamp firstSeen) {
    }

    @Override
    public List<Detection> detect(UUID tenantId, LocalDate today) {
        LocalDate since = today.minusDays(days);
        List<Spend> spends = jdbc.sql("""
                select entity_key, entity_type, linked_id, payload::text as payload, first_seen from ingest_entity
                where entity_type in ('INVOICE', 'PAYMENT') and linked_id is not null
                """).query((rs, n) -> spend(rs.getString("entity_key"), rs.getString("entity_type"),
                        rs.getObject("linked_id", UUID.class), rs.getString("payload"),
                        rs.getTimestamp("first_seen")))
                .list().stream().filter(s -> s != null && !s.day().isBefore(since) && !s.day().isAfter(today))
                .toList();
        Map<UUID, String> names = software.vendorNames();
        List<Detection> found = new ArrayList<>();
        List<Spend> invoices = spends.stream().filter(s -> s.type().equals("INVOICE"))
                .sorted(Comparator.comparing(Spend::firstSeen).thenComparing(Spend::key)).toList();
        Set<String> duplicates = duplicates(invoices, names, found);
        overcharges(invoices.stream().filter(i -> !duplicates.contains(i.key())).toList(), names, found);
        List<Spend> payments = spends.stream()
                .filter(s -> s.type().equals("PAYMENT") && !"commission".equals(s.role())).toList();
        shadow(payments, names, found);
        bypass(payments, names, found);
        return found;
    }

    private Spend spend(String key, String type, UUID vendorId, String payload, Timestamp firstSeen) {
        JsonNode p;
        try {
            p = JSON.readTree(payload);
        } catch (IOException e) {
            return null;
        }
        BigDecimal amount = decimal(text(p, type.equals("INVOICE") ? "total" : "amount")).orElse(null);
        if (amount == null) {
            return null;
        }
        Optional<LocalDate> stated = text(p, "date").flatMap(SpendAuditRules::day);
        LocalDate day = stated.orElse(firstSeen.toInstant().atOffset(ZoneOffset.UTC).toLocalDate());
        String currency = text(p, "currency").map(c -> c.toUpperCase(Locale.ROOT)).orElse("RUB");
        BigDecimal rub = currency.equals("RUB") || currency.equals("RUR") ? amount
                : rates.toRub(amount, currency, day).orElse(null);
        boolean card = text(p, "card").isPresent() || "CARD".equalsIgnoreCase(text(p, "kind").orElse(""));
        String source = text(p, "source").or(() -> text(p, "file_name")).orElse("");
        return new Spend(key, type, vendorId, amount, currency, rub, day, stated.isPresent(),
                text(p, "number").orElse(""), text(p, "purpose").orElse(""), card, text(p, "mcc").orElse(""),
                text(p, "role").orElse("payment"), source, firstSeen);
    }

    private static String number(String number) {
        return number.toLowerCase(Locale.ROOT).replaceAll("[\\s№#]", "");
    }

    private static Set<String> duplicates(List<Spend> invoices, Map<UUID, String> names, List<Detection> found) {
        Set<String> duplicates = new HashSet<>();
        for (int j = 0; j < invoices.size(); j++) {
            Spend later = invoices.get(j);
            for (int i = 0; i < j; i++) {
                Spend earlier = invoices.get(i);
                if (duplicates.contains(earlier.key()) || !earlier.vendorId().equals(later.vendorId())) {
                    continue;
                }
                boolean numbered = !later.number().isBlank() && !earlier.number().isBlank();
                boolean same = numbered ? number(later.number()).equals(number(earlier.number()))
                        : later.dated() && earlier.dated() && later.day().equals(earlier.day())
                                && later.amount().compareTo(earlier.amount()) == 0
                                && later.currency().equals(earlier.currency());
                if (!same) {
                    continue;
                }
                duplicates.add(later.key());
                String vendor = names.getOrDefault(later.vendorId(), "");
                String what = later.number().isBlank() ? "от " + later.day() : "№ " + later.number();
                found.add(new Detection(DUPLICATE, DUPLICATE + ":" + later.key(),
                        "Счёт пришёл дважды: " + vendor + " " + what,
                        "Счёт " + vendor + " " + what + " на " + money(later.amount(), later.currency())
                                + " получен ещё раз" + sources(earlier, later)
                                + ". Проверьте, что его не оплатили дважды.",
                        later.rub() == null ? BigDecimal.ZERO : later.rub(), null));
                break;
            }
        }
        return duplicates;
    }

    private static String sources(Spend a, Spend b) {
        if (a.source().isBlank() || b.source().isBlank()) {
            return "";
        }
        return a.source().equals(b.source()) ? " из " + a.source() : " (" + a.source() + " и " + b.source() + ")";
    }

    private void overcharges(List<Spend> invoices, Map<UUID, String> names, List<Detection> found) {
        Map<UUID, List<SoftwareInventory.Billing>> byVendor = software.billing().stream()
                .collect(Collectors.groupingBy(SoftwareInventory.Billing::vendorId));
        for (Spend invoice : invoices) {
            List<SoftwareInventory.Billing> billing = byVendor.getOrDefault(invoice.vendorId(), List.of());
            Set<Boolean> periods = billing.stream().map(SoftwareInventory.Billing::yearly).collect(Collectors.toSet());
            if (invoice.rub() == null || periods.size() != 1) {
                continue;
            }
            boolean yearly = periods.iterator().next();
            BigDecimal expected = billing.stream().map(SoftwareInventory.Billing::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (expected.signum() <= 0 || !above(invoice.rub(), expected, 1) || prepaid(invoice.rub(), expected)) {
                continue;
            }
            BigDecimal over = invoice.rub().subtract(expected);
            String vendor = names.getOrDefault(invoice.vendorId(), "");
            String what = invoice.number().isBlank() ? "от " + invoice.day() : "№ " + invoice.number();
            found.add(new Detection(OVERCHARGE, OVERCHARGE + ":" + invoice.key(),
                    "Счёт больше подписок: " + vendor + " " + what,
                    "Счёт " + vendor + " " + what + " на " + money(invoice.rub(), "RUB") + ", а подписки стоят "
                            + money(expected, "RUB") + (yearly ? " в год" : " в месяц")
                            + ". Сверьте счёт с договором и числом мест.",
                    yearly ? over.divide(TWELVE, 2, RoundingMode.HALF_EVEN) : over,
                    billing.size() == 1 ? billing.getFirst().subscriptionId() : null));
        }
    }

    private boolean above(BigDecimal amount, BigDecimal expected, int periods) {
        BigDecimal limit = expected.multiply(BigDecimal.valueOf(periods)).multiply(BigDecimal.ONE.add(tolerance));
        return amount.compareTo(limit) > 0;
    }

    /** Two to twelve periods paid at once. */
    private boolean prepaid(BigDecimal amount, BigDecimal expected) {
        for (int k = 2; k <= 12; k++) {
            BigDecimal whole = expected.multiply(BigDecimal.valueOf(k));
            if (amount.subtract(whole).abs().compareTo(whole.multiply(tolerance)) <= 0) {
                return true;
            }
        }
        return false;
    }

    private void shadow(List<Spend> payments, Map<UUID, String> names, List<Detection> found) {
        Set<UUID> registered = software.registeredVendors();
        Map<UUID, List<Spend>> byVendor = new LinkedHashMap<>();
        for (Spend p : payments) {
            String vendor = names.getOrDefault(p.vendorId(), "");
            boolean saas = KnownServices.saas(vendor) || SOFTWARE_MCC.contains(p.mcc());
            if (registered.contains(p.vendorId()) || !saas || ai(vendor, p).isPresent()) {
                continue;
            }
            byVendor.computeIfAbsent(p.vendorId(), k -> new ArrayList<>()).add(p);
        }
        byVendor.forEach((vendorId, paid) -> {
            String vendor = names.getOrDefault(vendorId, "");
            BigDecimal total = sum(paid);
            BigDecimal month = total.multiply(BigDecimal.valueOf(30))
                    .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_EVEN);
            found.add(new Detection(SHADOW, SHADOW + ":" + vendorId, "Теневая подписка: " + vendor,
                    "За " + days + " дней " + paid.size() + " " + payments(paid.size()) + " " + vendor + " на "
                            + money(total, "RUB") + ", а подписки или договора в реестре нет. Внесите подписку "
                            + "в реестр, если сервис нужен, или откажитесь от него.", month, null));
        });
    }

    private Optional<String> ai(String vendor, Spend p) {
        return KnownServices.aiProvider(vendor).or(() -> KnownServices.aiProvider(p.purpose()));
    }

    private void bypass(List<Spend> payments, Map<UUID, String> names, List<Detection> found) {
        Set<String> routed = new TreeSet<>();
        for (String v : gateway.vendors()) {
            routed.add(Names.clean(v));
            KnownServices.aiProvider(v).ifPresent(routed::add);
        }
        Map<UUID, List<Spend>> byVendor = new LinkedHashMap<>();
        Map<UUID, Set<String>> why = new LinkedHashMap<>();
        for (Spend p : payments) {
            String vendor = names.getOrDefault(p.vendorId(), "");
            Optional<String> provider = ai(vendor, p);
            if (provider.isEmpty()) {
                continue;
            }
            boolean throughGateway = routed.contains(provider.get()) || routed.contains(Names.clean(vendor));
            if (!p.card() && throughGateway) {
                continue;
            }
            byVendor.computeIfAbsent(p.vendorId(), k -> new ArrayList<>()).add(p);
            why.computeIfAbsent(p.vendorId(), k -> new TreeSet<>())
                    .add(p.card() ? "оплата картой" : "поставщика нет в мандатах шлюза");
        }
        byVendor.forEach((vendorId, paid) -> {
            String vendor = names.getOrDefault(vendorId, "");
            found.add(new Detection(AI_BYPASS, AI_BYPASS + ":" + vendorId, "ИИ-расходы мимо шлюза: " + vendor,
                    "За " + days + " дней " + paid.size() + " " + payments(paid.size()) + " " + vendor + " на "
                            + money(sum(paid), "RUB") + " прошли мимо шлюза (" + String.join(", ", why.get(vendorId))
                            + "): у них нет бюджета, мандата и журнала. Переведите эти расходы на шлюз CostGuard.",
                    BigDecimal.ZERO, null));
        });
    }

    private static BigDecimal sum(List<Spend> spends) {
        return spends.stream().map(Spend::rub).filter(r -> r != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String payments(int n) {
        int last = n % 10;
        int lastTwo = n % 100;
        if (last == 1 && lastTwo != 11) {
            return "платёж";
        }
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "платежа" : "платежей";
    }

    private static String money(BigDecimal amount, String currency) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString() + " " + currency;
    }

    private static Optional<String> text(JsonNode p, String field) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? Optional.empty() : Optional.of(v.asText().strip());
    }

    private static Optional<BigDecimal> decimal(Optional<String> text) {
        try {
            return text.map(t -> new BigDecimal(t.replace(" ", "").replace(',', '.')));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> day(String text) {
        try {
            return Optional.of(LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
