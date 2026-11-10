// Прогноз из ядра и сценарии «что если» (ADR 0034): прогноз публикует еженедельная задача costguard-forecast,
// сценарии считает ядро по реестру подписок.
import { useEffect, useMemo, useState } from 'react';
import { ApiError, api } from '../api/http';
import type { ForecastSeries, PublishedForecast, ScenarioChange, ScenarioResult } from '../api/generated/core';
import { useStore } from '../api/store';
import { Card, Empty, Field, Stat } from '../components/ui';
import { LineChart, type LinePoint } from '../components/charts';
import { fmtDateTime, fmtMonth, fmtRub } from '../lib';

const MODEL: Record<string, string> = {
  holt: 'Хольт', ets_damped: 'ETS с затухающим трендом', theta: 'Theta', naive: 'повтор месяца',
  seasonal_naive: 'тот же месяц год назад', mean: 'среднее', empty: 'нет данных',
};
const pct = (v?: number) => (v == null ? '—' : `${Math.round(v * 100)}%`);

function points(series: ForecastSeries): LinePoint[] {
  return [
    ...(series.history ?? []).map((h) => ({ label: fmtMonth(h.month), value: h.amount })),
    ...(series.points ?? []).map((p) => ({ label: fmtMonth(p.month), value: p.value, low: p.low, high: p.high, forecast: true })),
  ];
}

export function ForecastPanel() {
  const { state } = useStore();
  const [published, setPublished] = useState<PublishedForecast | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [freeSeats, setFreeSeats] = useState(true);
  const [yearly, setYearly] = useState(false);
  const [cancel, setCancel] = useState<string[]>([]);
  const [result, setResult] = useState<ScenarioResult | null>(null);

  useEffect(() => {
    api<PublishedForecast>('GET', '/api/v1/analytics/forecast')
      .then(setPublished)
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) setPublished(null);
        else setError(e instanceof ApiError ? e.message : 'Нет связи с сервером');
      });
  }, []);

  const changes = useMemo<ScenarioChange[]>(() => [
    ...(freeSeats ? [{ kind: 'freeSeats' as const }] : []),
    ...(yearly ? [{ kind: 'yearly' as const, discount: 0.17 }] : []),
    ...cancel.map((id) => ({ kind: 'cancel' as const, subscriptionId: id })),
  ], [freeSeats, yearly, cancel]);

  useEffect(() => {
    let alive = true;
    api<ScenarioResult>('POST', '/api/v1/analytics/scenarios', { changes })
      .then((r) => { if (alive) setResult(r); })
      .catch(() => { if (alive) setResult(null); });
    return () => { alive = false; };
  }, [changes]);

  const f = published?.forecast;
  const next = f?.company?.points?.[0];
  return (
    <>
      <div className="stats">
        <Stat label="Прогноз на следующий месяц" value={next ? fmtRub(next.value) : '—'} hint={f ? `${MODEL[f.company?.model ?? ''] ?? f.company?.model}, ошибка на истории ${pct(f.company?.wape)}` : 'прогноз ещё не опубликован'} />
        <Stat label="Ожидаемая экономия по находкам" value={f?.savings?.[0] ? `${fmtRub(f.savings[0].expected)}/мес` : '—'} hint={f?.savings?.[0] ? `из них подтверждено ${fmtRub(f.savings[0].confirmed)}` : 'появится с прогнозом'} />
        <Stat label="Экономия по сценарию" value={result ? `${fmtRub(result.monthlySavings ?? 0)}/мес` : '—'} hint={result ? `${fmtRub(result.totalSavings ?? 0)} за ${result.months?.length ?? 0} мес.` : ''} />
      </div>

      <Card title="Расходы по месяцам и прогноз">
        {error ? <Empty>Не удалось загрузить прогноз. {error}</Empty>
          : published === undefined ? <p className="muted">Загрузка…</p>
            : published === null ? <Empty>Прогноз ещё не опубликован: его строит еженедельная задача costguard-forecast по расходам из ClickHouse.</Empty>
              : (
                <>
                  <LineChart points={points(f!.company!)} title="Расходы компании: факт и прогноз" />
                  <p className="muted small">
                    Опубликован {fmtDateTime(published.publishedAt ?? '')}. Модель выбрана проверкой на истории: {MODEL[f!.company!.model ?? '']} (WAPE {pct(f!.company!.wape)}
                    {f!.company!.challenger ? `, следующая — ${MODEL[f!.company!.challenger] ?? f!.company!.challenger}, ${pct(f!.company!.challengerWape)}` : ''}).
                    Отделы согласованы с компанией (MinT): их прогнозы в сумме дают прогноз компании.
                  </p>
                  {(f!.departments?.length ?? 0) > 0 && (
                    <div className="table-wrap">
                      <table>
                        <thead><tr><th>Отдел</th><th>Модель</th><th className="num">WAPE</th><th className="num">Прогноз на {next ? fmtMonth(next.month) : ''}</th><th className="num">По реестру подписок</th></tr></thead>
                        <tbody>
                          {f!.departments!.map((d) => (
                            <tr key={d.departmentId ?? 'none'}>
                              <td>{d.name}</td><td>{MODEL[d.model ?? ''] ?? d.model}</td><td className="num">{pct(d.wape)}</td>
                              <td className="num">{fmtRub(d.points?.[0]?.value ?? 0)}</td><td className="num">{fmtRub(d.points?.[0]?.baseline ?? 0)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              )}
      </Card>

      <Card title="Сценарий «что если»">
        <div className="form">
          <label className="switch"><input type="checkbox" checked={freeSeats} onChange={(e) => setFreeSeats(e.target.checked)} /> <span>Оплачивать только занятые места</span></label>
          <label className="switch"><input type="checkbox" checked={yearly} onChange={(e) => setYearly(e.target.checked)} /> <span>Перевести помесячные подписки на годовую оплату со скидкой 17% с даты продления</span></label>
          <Field label="Отказаться от подписок">
            <div className="checks">
              {state.subscriptions.map((s) => (
                <label key={s.id} className="switch small">
                  <input type="checkbox" checked={cancel.includes(s.id)} onChange={(e) => setCancel(e.target.checked ? [...cancel, s.id] : cancel.filter((x) => x !== s.id))} />
                  <span>{state.vendors.find((v) => v.id === s.vendorId)?.name} {s.product}</span>
                </label>
              ))}
            </div>
          </Field>
          {result && (
            <>
              <div className="scenario-total">Итого: <strong>{fmtRub(result.monthlySavings ?? 0)}</strong> в месяц · <strong>{fmtRub(result.totalSavings ?? 0)}</strong> за {result.months?.length} мес.</div>
              {(result.effects?.length ?? 0) > 0 && (
                <ul className="list small">
                  {result.effects!.map((e, i) => <li key={i} className="list-row"><span>{e.subscription}: {e.description}</span><span>{fmtRub(e.monthlySavings ?? 0)}/мес</span></li>)}
                </ul>
              )}
              {result.onForecast && (
                <details className="small">
                  <summary>Прогноз с учётом сценария</summary>
                  <div className="table-wrap"><table><thead><tr><th>Месяц</th><th className="num">Прогноз</th><th className="num">По сценарию</th></tr></thead>
                    <tbody>{result.months!.map((m) => <tr key={m.month}><td>{fmtMonth(m.month ?? '')}</td><td className="num">{fmtRub(m.forecast ?? 0)}</td><td className="num">{fmtRub(m.forecastAfter ?? 0)}</td></tr>)}</tbody></table></div>
                </details>
              )}
            </>
          )}
        </div>
      </Card>
    </>
  );
}
