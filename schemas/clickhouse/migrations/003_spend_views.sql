-- What reports and forecasts read. FINAL collapses records replaced by a newer version.
CREATE VIEW IF NOT EXISTS spend_by_month AS
SELECT tenant_id,
       toStartOfMonth(charge_period_start) AS month,
       billing_currency                    AS currency,
       source,
       service_provider_name               AS provider,
       sum(billed_cost)                    AS billed,
       count()                             AS records
FROM cost_records FINAL
GROUP BY tenant_id, month, currency, source, provider;

CREATE VIEW IF NOT EXISTS agent_spend_by_day AS
SELECT tenant_id,
       agent_id,
       toDate(occurred_at)                        AS day,
       currency,
       countIf(decision = 'ALLOWED')              AS allowed,
       countIf(decision = 'DENIED')               AS denied,
       sumIf(coalesce(actual_cost, estimated_cost), decision = 'ALLOWED') AS spent,
       sum(coalesce(input_tokens, 0))             AS input_tokens,
       sum(coalesce(output_tokens, 0))            AS output_tokens
FROM agent_operations FINAL
GROUP BY tenant_id, agent_id, day, currency;
