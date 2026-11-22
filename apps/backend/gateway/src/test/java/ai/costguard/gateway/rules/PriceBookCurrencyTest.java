package ai.costguard.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.BudgetLevel;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.ExchangeRate;
import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.budget.BudgetTree;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0072: a price is used in the currency of the company's budgets, to the digits the catalog keeps. */
class PriceBookCurrencyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);
    private final RateBook rates = new RateBook();
    private final BudgetTree budgets = new BudgetTree();
    private final PriceBook prices = new PriceBook(rates, budgets);
    private final UUID tenant = UUID.randomUUID();

    private void budgetsIn(String currency) {
        budgets.apply(BudgetLimit.newBuilder().setTenantId(tenant).setBudgetId(UUID.randomUUID())
                .setParentBudgetId(null).setLevel(BudgetLevel.COMPANY).setRefId(null)
                .setLimitAmount(new BigDecimal("1000.000000")).setCurrency(currency)
                .setCorrection(new BigDecimal("0.000000")).setPeriodStart(LocalDate.of(2026, 1, 1))
                .setPeriodEnd(LocalDate.of(2026, 12, 31)).setVersion(1).build());
    }

    private void rate(String currency, String roubles, LocalDate day) {
        rates.apply(ExchangeRate.newBuilder().setCurrency(currency).setRoubles(new BigDecimal(roubles))
                .setRateDate(day).build());
    }

    /** $0.15 per million input tokens: zero at the six decimals of unit_price, exact in unit_price_exact. */
    private void miniInputPrice(String currency) {
        prices.apply(CatalogPrice.newBuilder().setTenantId(tenant).setSkuId("mini:in").setProvider("OpenAI")
                .setModel("gpt-5-mini").setUnit(PriceUnit.INPUT_TOKEN).setUnitPrice(new BigDecimal("0.000000"))
                .setUnitPriceExact(new BigDecimal("0.000000150")).setCurrency(currency)
                .setValidFrom(LocalDate.of(2026, 1, 1)).build());
    }

    private BigDecimal unit() {
        return prices.unitPrice(tenant, "OpenAI", "gpt-5-mini", PriceUnit.INPUT_TOKEN, DAY).orElse(null);
    }

    @Test
    void aCompanyInDollarsPaysADollarPriceToTheLastDigit() {
        budgetsIn("USD");
        miniInputPrice("USD");
        assertThat(unit()).isEqualByComparingTo("0.00000015");
    }

    @Test
    void aCompanyInRoublesPaysADollarPriceConverted() {
        budgetsIn("RUB");
        rate("USD", "80", DAY.minusDays(1));
        miniInputPrice("USD");
        assertThat(unit()).isEqualByComparingTo("0.000012");
    }

    @Test
    void aPriceWithoutARateIsNoPrice() {
        budgetsIn("RUB");
        miniInputPrice("USD");
        assertThat(unit()).isNull();
    }

    @Test
    void twoForeignCurrenciesMeetThroughTheRouble() {
        budgetsIn("EUR");
        rate("USD", "80", DAY);
        rate("EUR", "88", DAY);
        assertThat(prices.inCompanyCurrency(tenant, new BigDecimal("110"), "USD")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("100"));
    }

    @Test
    void anOlderRateDoesNotReplaceANewerOne() {
        budgetsIn("RUB");
        rate("USD", "90", DAY);
        rate("USD", "70", DAY.minusDays(3));
        assertThat(prices.inCompanyCurrency(tenant, BigDecimal.ONE, "USD")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("90"));
    }

    @Test
    void withoutBudgetsAPriceStaysInItsOwnCurrency() {
        miniInputPrice("USD");
        assertThat(unit()).isEqualByComparingTo("0.00000015");
    }

    @Test
    void anOlderCorePriceWithoutTheExactDigitsIsReadAsBefore() {
        budgetsIn("RUB");
        prices.apply(CatalogPrice.newBuilder().setTenantId(tenant).setSkuId("gpt:out").setProvider("OpenAI")
                .setModel("gpt-5").setUnit(PriceUnit.OUTPUT_TOKEN).setUnitPrice(new BigDecimal("0.000800"))
                .setCurrency("RUB").setValidFrom(LocalDate.of(2026, 1, 1)).build());
        assertThat(prices.unitPrice(tenant, "OpenAI", "gpt-5", PriceUnit.OUTPUT_TOKEN, DAY))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.0008"));
    }
}
