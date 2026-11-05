import { useMemo, useState } from 'react';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Report } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Empty, Field, Modal, PageHeader, Stat, Tabs } from '../components/ui';
import { LineChart, type LinePoint } from '../components/charts';
import { downloadText, fmtDate, fmtMonth, fmtRub, toCsv } from '../lib';

type Series = 'total' | 'software' | 'agents';

export function Analytics() {
  const { state, actions } = useStore();
  const { user, canEdit } = useAuth();
  const L = useLookup();
  const [series, setSeries] = useState<Series>('total');
  const [seatCut, setSeatCut] = useState(15);
  const [annual, setAnnual] = useState(true);
  const [agentCut, setAgentCut] = useState(10);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('Расходы и экономия за ноябрь 2026');
  const [format, setFormat] = useState<'xlsx' | 'pdf'>('xlsx');
  const [period, setPeriod] = useState('2026-11');

  const points = useMemo<LinePoint[]>(() => {
    const val = (p: { software: number; agents: number }) => (series === 'total' ? p.software + p.agents : p[series]);
    const hist = state.costHistory.map((p) => ({ label: fmtMonth(p.month), value: val(p) }));
    // Прогноз: тренд за последние 6 месяцев (в рабочей версии — ETS/Theta из сервиса прогнозов)
    const last = state.costHistory.slice(-6).map(val);
    const slope = (last[last.length - 1] - last[0]) / (last.length - 1);
    const base = last[last.length - 1];
    const months = ['2026-11', '2026-12', '2027-01', '2027-02'];
    const fc = months.map((m, i) => {
      const v = Math.round(base + slope * (i + 1));
      const spread = 0.04 + i * 0.03;
      return { label: fmtMonth(m), value: v, forecast: true, low: Math.round(v * (1 - spread)), high: Math.round(v * (1 + spread)) };
    });
    return [...hist, ...fc];
  }, [state.costHistory, series]);

  const softwareMonthly = state.subscriptions.reduce((s, x) => s + x.seats * x.pricePerSeat, 0);
  const monthlyOnly = state.subscriptions.filter((s) => s.billingPeriod === 'month').reduce((s, x) => s + x.seats * x.pricePerSeat, 0);
  const agentMonthly = state.costHistory[state.costHistory.length - 1].agents;
  const savingSeats = Math.round(softwareMonthly * (seatCut / 100));
  const savingAnnual = annual ? Math.round(monthlyOnly * 0.17) : 0;
  const savingAgents = Math.round(agentMonthly * (agentCut / 100));
  const scenarioTotal = savingSeats + savingAnnual + savingAgents;
  const nextForecast = points.find((p) => p.forecast)?.value ?? 0;

  const download = (r: Report) => {
    const rows: (string | number)[][] = [
      ['Отчёт', r.name], ['Период', r.period], ['Сформирован', fmtDate(r.createdAt)], [],
      ['Подписка', 'Поставщик', 'Отдел', 'Мест', 'В месяц, ₽'],
      ...state.subscriptions.map((s) => [s.product, L.vendor(s.vendorId)?.name ?? '', L.dept(s.departmentId)?.name ?? '', s.seats, s.seats * s.pricePerSeat]),
      [], ['Агент', 'Отдел', 'Потрачено, ₽', 'Лимит, ₽'],
      ...state.agents.map((a) => { const b = L.budgetOfAgent(a.id); return [a.name, L.dept(a.departmentId)?.name ?? '', Math.round(b?.spent ?? 0), b?.limit ?? 0]; }),
      [], ['Находка', 'Статус', 'Экономия в месяц, ₽'],
      ...state.findings.map((f) => [f.title, f.status, f.expectedSavings]),
    ];
    if (r.format === 'xlsx') {
      downloadText(`${r.name}.csv`, toCsv(rows), 'text/csv');
      return;
    }
    const w = window.open('', '_blank');
    if (!w) return;
    const esc = (s: string | number) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]!));
    w.document.write(`<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>${esc(r.name)}</title><style>body{font:14px system-ui;margin:32px;color:#111}table{border-collapse:collapse;width:100%}td{border-bottom:1px solid #ddd;padding:4px 8px}</style></head><body><h1>${esc(r.name)}</h1><table>${rows.map((row) => `<tr>${row.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</table></body></html>`);
    w.document.close();
    w.focus();
    w.print();
  };

  return (
    <>
      <PageHeader title="Прогнозы и отчёты" subtitle="Прогноз расходов, сценарии экономии «что если» и отчёты для руководства" />
      <div className="stats">
        <Stat label="Прогноз на ноябрь" value={fmtRub(nextForecast)} hint={series === 'total' ? 'ПО и ИИ-агенты' : series === 'software' ? 'только ПО' : 'только ИИ-агенты'} />
        <Stat label="Рост ИИ-расходов за год" value={`×${(agentMonthly / state.costHistory[0].agents).toFixed(1)}`} hint={`${fmtRub(state.costHistory[0].agents)} → ${fmtRub(agentMonthly)} в месяц`} />
        <Stat label="Экономия по сценарию" value={`${fmtRub(scenarioTotal)}/мес`} hint={`${fmtRub(scenarioTotal * 12)} в год`} />
      </div>

      <Card title="Расходы по месяцам и прогноз" actions={<Tabs value={series} onChange={setSeries} items={[{ id: 'total', label: 'Всё' }, { id: 'software', label: 'ПО' }, { id: 'agents', label: 'ИИ-агенты' }]} />}>
        <LineChart points={points} title="Расходы по месяцам: факт и прогноз" />
        <details className="small">
          <summary>Таблица значений</summary>
          <div className="table-wrap"><table><thead><tr><th>Месяц</th><th>Тип</th><th className="num">Сумма</th></tr></thead>
            <tbody>{points.map((p) => <tr key={p.label}><td>{p.label}</td><td>{p.forecast ? 'прогноз' : 'факт'}</td><td className="num">{fmtRub(p.value)}</td></tr>)}</tbody></table></div>
        </details>
      </Card>

      <div className="grid-2">
        <Card title="Сценарий «что если»">
          <div className="form">
            <Field label={`Снять неиспользуемые места: ${seatCut}%`} hint={`экономия ${fmtRub(savingSeats)} в месяц`}>
              <input type="range" min={0} max={40} value={seatCut} onChange={(e) => setSeatCut(Number(e.target.value))} />
            </Field>
            <label className="switch"><input type="checkbox" checked={annual} onChange={(e) => setAnnual(e.target.checked)} /> <span>Перевести помесячные подписки на годовой тариф (−17%): {fmtRub(savingAnnual)}</span></label>
            <Field label={`Урезать бюджеты ИИ-агентов: ${agentCut}%`} hint={`экономия ${fmtRub(savingAgents)} в месяц`}>
              <input type="range" min={0} max={50} value={agentCut} onChange={(e) => setAgentCut(Number(e.target.value))} />
            </Field>
            <div className="scenario-total">Итого: <strong>{fmtRub(scenarioTotal)}</strong> в месяц · <strong>{fmtRub(scenarioTotal * 12)}</strong> в год</div>
          </div>
        </Card>

        <Card title="Отчёты" actions={canEdit('analytics') && <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>Сформировать</Button>}>
          {state.reports.length === 0 ? <Empty>Отчётов пока нет</Empty> : (
            <ul className="list">
              {state.reports.map((r) => (
                <li key={r.id} className="list-row">
                  <span>{r.name}<span className="muted small block">{r.createdBy}, {fmtDate(r.createdAt)}</span></span>
                  <span className="row-gap">
                    <Badge>{r.format.toUpperCase()}</Badge>
                    <Button variant="ghost" icon="download" onClick={() => download(r)}>Скачать</Button>
                    {canEdit('analytics') && <ConfirmButton variant="ghost" title="Удалить отчёт?" text={r.name} confirmLabel="Удалить" onConfirm={() => actions.deleteReport(r.id)}>Удалить</ConfirmButton>}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="muted small">В прототипе XLSX выгружается как CSV (откроется в Excel), PDF — через печать браузера. В рабочей версии файлы формирует сервис отчётов.</p>
        </Card>
      </div>

      <Modal open={creating} onClose={() => setCreating(false)} title="Новый отчёт" footer={
        <><Button variant="ghost" onClick={() => setCreating(false)}>Отмена</Button>
          <Button variant="primary" disabled={!name.trim()} onClick={() => { actions.createReport(name, format, period, user?.name ?? ''); setCreating(false); }}>Сформировать</Button></>
      }>
        <div className="form">
          <Field label="Название"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <div className="form-row">
            <Field label="Период"><select className="input" value={period} onChange={(e) => setPeriod(e.target.value)}>{['2026-11', '2026-10', '2026-09', '2026-Q3', '2026'].map((p) => <option key={p}>{p}</option>)}</select></Field>
            <Field label="Формат"><select className="input" value={format} onChange={(e) => setFormat(e.target.value as 'xlsx' | 'pdf')}><option value="xlsx">XLSX</option><option value="pdf">PDF</option></select></Field>
          </div>
        </div>
      </Modal>
    </>
  );
}
