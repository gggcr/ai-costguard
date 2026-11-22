package ai.costguard.core.shared.names;

/**
 * How the texts of the core name a currency after an amount (ADR 0072): ₽ for the rouble, as they always did, and
 * the ISO code for any other, so that a company in dollars never reads its money as roubles.
 */
public final class Currencies {

    /** The rouble, for a company that never said its currency. */
    public static final String DEFAULT = "RUB";

    private Currencies() {
    }

    /** " ₽", or a space and the code: what follows the number. */
    public static String suffix(String currency) {
        String code = currency == null ? "" : currency.strip();
        return code.isEmpty() || code.equals("RUB") || code.equals("RUR") ? " ₽" : " " + code;
    }
}
