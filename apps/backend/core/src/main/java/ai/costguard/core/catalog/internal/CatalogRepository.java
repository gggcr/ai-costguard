package ai.costguard.core.catalog.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CatalogRepository {

    private static final String PRICE_COLUMNS = "id, sku_id, vendor, model, unit, unit_price, currency, valid_from";

    private static final RowMapper<Price> PRICE = (rs, n) -> new Price(rs.getObject("id", UUID.class),
            rs.getString("sku_id"), rs.getString("vendor"), rs.getString("model"),
            PriceUnit.valueOf(rs.getString("unit")), rs.getBigDecimal("unit_price").stripTrailingZeros(),
            rs.getString("currency"), rs.getObject("valid_from", LocalDate.class));

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The price in force on {@code date} for each SKU. */
    List<Price> current(LocalDate date) {
        return jdbc.sql("select distinct on (sku_id) " + PRICE_COLUMNS + """
                 from price where valid_from <= ?
                 order by sku_id, valid_from desc
                """).param(date).query(PRICE).list();
    }

    List<Price> history(String skuId) {
        return jdbc.sql("select " + PRICE_COLUMNS + " from price where sku_id = ? order by valid_from desc")
                .param(skuId).query(PRICE).list();
    }

    void insert(UUID tenantId, UUID id, NewPrice p) {
        jdbc.sql("""
                insert into price (id, tenant_id, sku_id, vendor, model, unit, unit_price, currency, valid_from)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, p.skuId(), p.vendor(), p.model(), p.unit().name(), p.unitPrice(),
                p.currency(), p.validFrom()).update();
    }

    /** Re-reading a day replaces it: the bank sometimes publishes a correction. */
    void saveRate(FxRate rate, String base) {
        jdbc.sql("""
                insert into fx_rate (rate_date, base, currency, rate) values (?, ?, ?, ?)
                on conflict (rate_date, base, currency) do update set rate = excluded.rate
                """).params(rate.date(), base, rate.currency(), rate.rate()).update();
    }

    /** The price of one unit of {@code currency} in {@code base} in force on {@code date} (ADR 0075). */
    Optional<FxRate> rateOn(String currency, LocalDate date, String base) {
        return jdbc.sql("""
                select rate_date, currency, rate from fx_rate
                where currency = ? and base = ? and rate_date <= ? order by rate_date desc limit 1
                """).params(currency, base, date)
                .query((rs, n) -> new FxRate(rs.getObject("rate_date", LocalDate.class), rs.getString("currency"),
                        rs.getBigDecimal("rate").stripTrailingZeros()))
                .optional();
    }

    List<FxRate> ratesOn(LocalDate date, String base) {
        return jdbc.sql("""
                select distinct on (currency) rate_date, currency, rate from fx_rate
                where rate_date <= ? and base = ? order by currency, rate_date desc
                """).params(date, base)
                .query((rs, n) -> new FxRate(rs.getObject("rate_date", LocalDate.class), rs.getString("currency"),
                        rs.getBigDecimal("rate").stripTrailingZeros()))
                .list();
    }
}
