-- Monthly spend of each department, for the forecasts that reconcile departments with the company (ADR 0034).
-- Records with no department form the department '' ("no department"), so the departments always add up to the
-- company and the hierarchy of the forecast holds.
CREATE VIEW IF NOT EXISTS spend_by_department_month AS
SELECT tenant_id,
       toStartOfMonth(charge_period_start)        AS month,
       billing_currency                           AS currency,
       ifNull(toString(department_id), '')        AS department,
       sum(billed_cost)                           AS billed
FROM cost_records FINAL
GROUP BY tenant_id, month, currency, department;
