package ai.costguard.core.cabinet.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code seatsFromPool}: licenses given without buying a seat ({@code reassignedIdle} of them taken back from an idle
 * colleague); {@code purchasesRefused}: purchases not made because the company had the program. Sums are a month.
 */
record CabinetSavings(LocalDate since, int seatsFromPool, int reassignedIdle, BigDecimal seatsMonthly,
        int purchasesRefused, BigDecimal purchasesMonthly, BigDecimal totalMonthly, List<Month> byMonth) {

    record Month(String month, BigDecimal saved) {
    }
}
