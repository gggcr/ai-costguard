// Зарплата (ADR 0054): сотрудник видит свои выплаты и ближайшие дни выплат; owner и finance загружают ведомость.
import { useState } from 'react';
import type { PayrollUnmatched } from '../../api/generated/core';
import { api, uploadForm } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, Empty, Field, Stat } from '../../components/ui';
import { currentCurrency, fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface Payment { id: string; employeeName: string; kind: string; period: string; payDate: string; amount: number; currency: string; source: string }
interface MyPayroll { schedule: { advanceDay: number; salaryDay: number }; upcoming: { kind: string; date: string }[]; payments: Payment[] }
interface ImportResult { stored: number; skipped: { row: number; reason: string }[] }

const KIND: Record<string, string> = { advance: 'Аванс', salary: 'Зарплата', vacation_pay: 'Отпускные', bonus: 'Премия', other: 'Другое' };
const REASON: Record<string, string> = { 'employee not found': 'сотрудник не найден', 'ambiguous employee': 'адрес у нескольких сотрудников, выплата не назначена', 'bad email': 'неверный адрес почты', 'bad date': 'неверная дата', 'bad amount': 'неверная сумма' };

const money = (n: number, currency = currentCurrency()) =>
  new Intl.NumberFormat('ru-RU', { style: 'currency', currency, maximumFractionDigits: 2 }).format(n);
const month = (iso: string) => new Date(iso).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

export function MyPay() {
  const { data, error } = useApi<MyPayroll>('/api/v1/me/payroll');
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  return (
    <>
      <div className="stats">
        {data.upcoming.map((u) => <Stat key={u.kind} label={u.kind === 'advance' ? 'Следующий аванс' : 'Следующая зарплата'} value={fmtDate(u.date)} hint={`по графику: ${u.kind === 'advance' ? data.schedule.advanceDay : data.schedule.salaryDay}-го, выходной — накануне`} />)}
      </div>
      <Card title="Мои выплаты">
        <p className="muted small">Видите только вы и бухгалтерия. В день выплаты приходит уведомление.</p>
        {data.payments.length === 0 ? <Empty>Выплат пока нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Дата</th><th>Вид</th><th>За месяц</th><th className="num">Сумма</th></tr></thead>
              <tbody>
                {data.payments.map((p) => (
                  <tr key={p.id}><td>{fmtDate(p.payDate)}</td><td><Badge>{KIND[p.kind] ?? p.kind}</Badge></td><td>{month(p.period)}</td><td className="num">{money(p.amount, p.currency)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

/** owner и finance: график выплат, загрузка ведомости, выплаты месяца. */
export function EmployerPayroll() {
  const { toast } = useStore();
  const schedule = useApi<{ advanceDay: number; salaryDay: number }>('/api/v1/payroll/schedule');
  const [shown, setShown] = useState(new Date().toISOString().slice(0, 7));
  const payments = useApi<Payment[]>(`/api/v1/payroll/payments?month=${shown}`);
  // выплаты, которые никто не получил (ADR 0070): адрес не найден, у нескольких сотрудников или не адрес
  const unmatched = useApi<PayrollUnmatched[]>('/api/v1/payroll/unmatched');
  const [advance, setAdvance] = useState<number | null>(null);
  const [salary, setSalary] = useState<number | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const a = advance ?? schedule.data?.advanceDay ?? 25;
  const s = salary ?? schedule.data?.salaryDay ?? 10;
  const save = async () => {
    try {
      await api('PUT', '/api/v1/payroll/schedule', { advanceDay: a, salaryDay: s });
      toast('График выплат сохранён', 'good');
      schedule.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const load = async () => {
    if (!file) return;
    try {
      const r = await uploadForm<ImportResult>('/api/v1/payroll/imports', file, {});
      setResult(r);
      payments.reload();
      unmatched.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <>
      <Card title="График выплат">
        <div className="form-row">
          <Field label="Аванс, число месяца"><input className="input" type="number" min={1} max={31} value={a} onChange={(e) => setAdvance(Number(e.target.value))} /></Field>
          <Field label="Зарплата, число месяца"><input className="input" type="number" min={1} max={31} value={s} onChange={(e) => setSalary(Number(e.target.value))} /></Field>
        </div>
        <Button variant="primary" onClick={() => { void save(); }}>Сохранить</Button>
      </Card>
      <Card title="Загрузить ведомость">
        <p className="muted small">Excel (.xlsx) или CSV, первая строка — заголовки: <b>Сотрудник</b> (ФИО или почта), <b>Вид</b> (аванс, зарплата, отпускные, премия), <b>Период</b>, <b>Дата выплаты</b>, <b>Сумма</b>. Из 1С:ЗУП выплаты приходят сами, если подключить её в «Подключённых системах».</p>
        <div className="form-row">
          <Field label="Файл"><input className="input" type="file" accept=".xlsx,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} /></Field>
        </div>
        <Button variant="primary" disabled={!file} onClick={() => { void load(); }}>Загрузить</Button>
        {result && (
          <p className="small">Загружено выплат: <b>{result.stored}</b>{result.skipped.length > 0 && <> · пропущено: {result.skipped.map((x) => `строка ${x.row} — ${REASON[x.reason] ?? x.reason}`).join('; ')}</>}</p>
        )}
      </Card>
      <Card title="Выплаты месяца" actions={<input className="input" type="month" value={shown} onChange={(e) => setShown(e.target.value)} />}>
        {payments.error ? <Empty>{payments.error}</Empty> : !payments.data ? <Empty>Загрузка…</Empty> : payments.data.length === 0 ? <Empty>Выплат за этот месяц нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Сотрудник</th><th>Вид</th><th>Дата</th><th className="num">Сумма</th><th>Источник</th></tr></thead>
              <tbody>
                {payments.data.map((p) => (
                  <tr key={p.id}><td>{p.employeeName}</td><td>{KIND[p.kind] ?? p.kind}</td><td>{fmtDate(p.payDate)}</td><td className="num">{money(p.amount, p.currency)}</td><td className="muted small">{p.source}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title="Выплаты без сотрудника">
        <p className="muted small">Эти выплаты никому не назначены: адрес из ведомости или 1С:ЗУП не найден, есть у нескольких сотрудников или записан с ошибкой. Исправьте адрес сотрудника или в источнике и загрузите выплату снова — строка исчезнет сама.</p>
        {unmatched.error ? <Empty>{unmatched.error}</Empty> : !unmatched.data ? <Empty>Загрузка…</Empty> : unmatched.data.length === 0 ? <Empty>Все выплаты назначены</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Кому (как в источнике)</th><th>Вид</th><th>Дата</th><th className="num">Сумма</th><th>Причина</th><th>Источник</th></tr></thead>
              <tbody>
                {unmatched.data.map((u) => (
                  <tr key={u.id}>
                    <td>{[u.reportedEmail, u.reportedName].filter(Boolean).join(' · ')}</td>
                    <td>{KIND[u.kind ?? ''] ?? u.kind}</td>
                    <td>{u.payDate ? fmtDate(u.payDate) : ''}</td>
                    <td className="num">{money(u.amount ?? 0, u.currency ?? currentCurrency())}</td>
                    <td><Badge>{REASON[u.reason ?? ''] ?? u.reason}</Badge>{u.reason === 'ambiguous employee' && (u.candidates ?? 0) > 1 ? <span className="muted small"> · сотрудников с этим адресом: {u.candidates}</span> : null}</td>
                    <td className="muted small">{u.source}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
