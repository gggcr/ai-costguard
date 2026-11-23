package ai.costguard.core.catalog.internal;

import ai.costguard.core.catalog.ExchangeRateChanged;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads today's rates of the configured source (the Bank of Russia or the ECB, ADR 0075) on start and every few
 * hours; a failure keeps the previous rates. Each load also goes to the gateway ({@code catalog.rates.v1}, ADR 0072),
 * which keeps the latest rate of every currency. {@code costguard.catalog.cbr.enabled=false} turns loading off.
 */
@Component
@ConditionalOnProperty(name = "costguard.catalog.cbr.enabled", havingValue = "true", matchIfMissing = true)
class CbrRatesLoader {

    private static final Logger log = LoggerFactory.getLogger(CbrRatesLoader.class);

    private final CatalogRepository catalog;
    private final RatesSource source;
    private final URI url;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CbrRatesLoader(CatalogRepository catalog,
            @Value("${costguard.catalog.rates.source:cbr}") String source,
            @Value("${costguard.catalog.rates.url:}") String url,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.catalog = catalog;
        this.source = RatesSource.of(source);
        this.url = url.isBlank() ? this.source.defaultUrl : URI.create(url);
        this.events = events;
        this.tx = tx;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${costguard.catalog.cbr.interval:PT6H}")
    void refresh() {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("{} answered {}, keeping previous rates", source, response.statusCode());
                return;
            }
            List<FxRate> rates = source.parse(response.body());
            tx.executeWithoutResult(status -> save(rates));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not load {} rates, keeping previous rates: {}", source, e.toString());
        }
    }

    /** In one transaction with the events, so the outbox sends them only once the rates are saved. */
    void save(List<FxRate> rates) {
        rates.forEach(r -> catalog.saveRate(r, source.base));
        rates.forEach(r -> events.publishEvent(new ExchangeRateChanged(r.currency(), r.rate(), r.date())));
    }
}
