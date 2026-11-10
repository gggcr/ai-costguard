package ai.costguard.core.analytics.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps what the forecast job publishes (ADR 0034). A forecast whose departments do not add up to the company is
 * refused: the screen shows both, and they would contradict each other. Cents of rounding are allowed, a kopeck per
 * department.
 */
@Service
class ForecastService {

    private static final BigDecimal KOPECK = new BigDecimal("0.01");

    private final ForecastRepository forecasts;
    private final TenantSession session;
    private final Clock clock;

    ForecastService(ForecastRepository forecasts, TenantSession session, Clock clock) {
        this.forecasts = forecasts;
        this.session = session;
        this.clock = clock;
    }

    @Transactional
    PublishedForecast publish(UUID tenantId, ForecastDocument document) {
        check(document);
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        forecasts.insert(tenantId, id, clock.instant(), document);
        return forecasts.newest().orElseThrow();
    }

    @Transactional(readOnly = true)
    PublishedForecast newest(UUID tenantId) {
        session.bind(tenantId);
        return forecasts.newest().orElseThrow(AnalyticsProblem.FORECAST_NOT_FOUND::failure);
    }

    private static void check(ForecastDocument d) {
        List<ForecastSeries> all = new java.util.ArrayList<>(d.departments());
        all.add(d.company());
        if (all.stream().anyMatch(s -> s.points().size() != d.horizon())) {
            throw AnalyticsProblem.FORECAST_DOES_NOT_ADD_UP.failure();
        }
        if (d.departments().isEmpty()) {
            return;
        }
        BigDecimal allowed = KOPECK.multiply(BigDecimal.valueOf(d.departments().size()));
        for (int h = 0; h < d.horizon(); h++) {
            int step = h;
            BigDecimal sum = d.departments().stream().map(s -> s.points().get(step).value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.subtract(d.company().points().get(h).value()).abs().compareTo(allowed) > 0) {
                throw AnalyticsProblem.FORECAST_DOES_NOT_ADD_UP.failure();
            }
        }
    }
}
