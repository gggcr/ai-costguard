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

    CatalogService(CatalogRepository catalog, TenantSession session, Clock clock, ApplicationEventPublisher events) {
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
        return catalog.ratesOn(date == null ? LocalDate.now(clock) : date);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> toRub(BigDecimal amount, String currency, LocalDate date) {
        if ("RUB".equals(currency)) {
            return Optional.of(amount);
        }
        return catalog.rateOn(currency, date).map(r -> amount.multiply(r.rate()).setScale(2, RoundingMode.HALF_EVEN));
    }

    @Transactional(readOnly = true)
    Conversion convert(BigDecimal amount, String currency, LocalDate date) {
        LocalDate on = date == null ? LocalDate.now(clock) : date;
        BigDecimal rub = toRub(amount, currency, on).orElseThrow(CatalogProblem.RATE_NOT_FOUND::failure);
        return new Conversion(amount, currency, on, rub);
    }
}
