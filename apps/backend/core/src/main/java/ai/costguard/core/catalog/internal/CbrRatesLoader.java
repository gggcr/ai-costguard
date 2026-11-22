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
 * Loads today's rates on start and every few hours; a failure keeps the previous rates. Each load also goes to the
 * gateway ({@code catalog.rates.v1}, ADR 0072), which keeps the latest rate of every currency.
 */
@Component
@ConditionalOnProperty(name = "costguard.catalog.cbr.enabled", havingValue = "true", matchIfMissing = true)
class CbrRatesLoader {

    private static final Logger log = LoggerFactory.getLogger(CbrRatesLoader.class);

    private final CatalogRepository catalog;
    private final URI source;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CbrRatesLoader(CatalogRepository catalog,
            @Value("${costguard.catalog.cbr.url:https://www.cbr.ru/scripts/XML_daily.asp}") URI source,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.catalog = catalog;
        this.source = source;
        this.events = events;
        this.tx = tx;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${costguard.catalog.cbr.interval:PT6H}")
    void refresh() {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("Bank of Russia answered {}, keeping previous rates", response.statusCode());
                return;
            }
            List<FxRate> rates = CbrRatesParser.parse(response.body());
            tx.executeWithoutResult(status -> save(rates));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not load Bank of Russia rates, keeping previous rates: {}", e.toString());
        }
    }

    /** In one transaction with the events, so the outbox sends them only once the rates are saved. */
    void save(List<FxRate> rates) {
        rates.forEach(catalog::saveRate);
        rates.forEach(r -> events.publishEvent(new ExchangeRateChanged(r.currency(), r.rate(), r.date())));
    }
}
