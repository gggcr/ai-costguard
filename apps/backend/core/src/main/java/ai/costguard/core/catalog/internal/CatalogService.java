package ai.costguard.core.catalog.internal;

import ai.costguard.core.catalog.CatalogPriceChanged;
import ai.costguard.core.catalog.ExchangeRates;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogService implements ExchangeRates {

    private final CatalogRepository catalog;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    /** The base of the configured source first, then the other one: rates loaded before a switch still convert. */
    private final List<String> bases;

    CatalogService(CatalogRepository catalog, TenantSession session, Clock clock, ApplicationEventPublisher events,
            @org.springframework.beans.factory.annotation.Value("${costguard.catalog.rates.source:cbr}") String source) {
        String base = RatesSource.of(source).base;
        this.bases = java.util.stream.Stream.concat(java.util.stream.Stream.of(base),
                java.util.Arrays.stream(RatesSource.values()).map(s -> s.base).filter(b -> !b.equals(base))).toList();
        this.catalog = catalog;
        this.session = session;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<Price> current(UUID tenantId, LocalDate date) {
        session.bind(tenantId);
        return catalog.current(date == null ? LocalDate.now(clock) : date);
    }

    @Transactional(readOnly = true)
    List<Price> history(UUID tenantId, String skuId) {
        session.bind(tenantId);
        return catalog.history(skuId);
    }

    /** A price change is a new row from a date, so past operations keep the price they were estimated with. */
    @Transactional
    Price add(UUID tenantId, NewPrice request) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            catalog.insert(tenantId, id, request);
        } catch (DuplicateKeyException e) {
            throw CatalogProblem.PRICE_EXISTS.failure();
        }
        events.publishEvent(new CatalogPriceChanged(tenantId, request.skuId(), request.vendor(), request.model(),
                request.unit().name(), request.unitPrice(), request.currency(), request.validFrom()));
        return catalog.history(request.skuId()).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    List<FxRate> rates(LocalDate date) {
        return catalog.ratesOn(date == null ? LocalDate.now(clock) : date, bases.getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to, LocalDate date) {
        String source = rouble(from);
        String target = rouble(to);
        if (source.equals(target)) {
            return Optional.of(amount);
        }
        for (String base : bases) {
            Optional<BigDecimal> perSource = inBase(source, date, base);
            Optional<BigDecimal> perTarget = inBase(target, date, base);
            if (perSource.isPresent() && perTarget.isPresent()) {
                return Optional.of(amount.multiply(perSource.get()).divide(perTarget.get(), 2, RoundingMode.HALF_EVEN));
            }
        }
        return Optional.empty();
    }

    private static String rouble(String currency) {
        String code = currency == null ? "" : currency.strip();
        return code.equals("RUR") ? "RUB" : code;
    }

    /** The price of one unit of the currency in {@code base}: roubles or euros (ADR 0075). */
    private Optional<BigDecimal> inBase(String currency, LocalDate date, String base) {
        return currency.equals(base) ? Optional.of(BigDecimal.ONE)
                : catalog.rateOn(currency, date, base).map(FxRate::rate);
    }

    private Optional<BigDecimal> toRub(BigDecimal amount, String currency, LocalDate date) {
        return convert(amount, currency, "RUB", date);
    }

    @Transactional(readOnly = true)
    Conversion convert(BigDecimal amount, String currency, LocalDate date) {
        LocalDate on = date == null ? LocalDate.now(clock) : date;
        BigDecimal rub = toRub(amount, currency, on).orElseThrow(CatalogProblem.RATE_NOT_FOUND::failure);
        return new Conversion(amount, currency, on, rub);
    }
}
