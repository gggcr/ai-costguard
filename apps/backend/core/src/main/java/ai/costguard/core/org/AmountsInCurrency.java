package ai.costguard.core.org;

/**
 * A module that keeps amounts without a currency of their own, in the company's currency: budgets, mandate limits,
 * subscription prices, invoices, the cost of equipment (ADR 0072). While any of them keeps one, the company's
 * currency stays as it is: changing it would make every such amount mean another sum.
 */
public interface AmountsInCurrency {

    /** Called inside a transaction bound to the tenant. */
    boolean holdsAmounts();
}
