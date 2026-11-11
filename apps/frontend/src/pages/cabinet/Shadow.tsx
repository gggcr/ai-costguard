// Теневые траты (ADR 0057): сотрудник легально сообщает о покупке софта своей картой; руководство видит только
// сводку — сервис, категорию и сумму, если платят хотя бы трое, — и никогда не видит, кто.
import { useState } from 'react';
import { api, uploadForm } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty, Field } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface Spend { id: string; service: string; category: string; amount: number; currency: string; paidOn: string; purpose: string | null }
interface Summary {
  minPeople: number;
  hiddenServices: number;
  services: { service: string; category: string; people: number; reports: number; total: number; currency: string; lastPaid: string | null; departments: { department: string; people: number; total: number }[] }[];
}

const money = (n: number, currency: string) => new Intl.NumberFormat('ru-RU', { style: 'currency', currency, maximumFractionDigits: 0 }).format(n);

export function MyShadowSpend() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<Spend[]>('/api/v1/me/shadow-spend');
  const [service, setService] = useState('');
  const [amount, setAmount] = useState('');
  const [paidOn, setPaidOn] = useState(new Date().toISOString().slice(0, 10));
  const [purpose, setPurpose] = useState('');
  const [file, setFile] = useState<File | null>(null);
  if (error) return <Empty>{error}</Empty>;
  const send = async () => {
    try {
      await api('POST', '/api/v1/me/shadow-spend', { service, amount: Number(amount.replace(',', '.')), paidOn, purpose });
      toast('Спасибо! Руководство увидит только сводку без имён', 'good');
      setService(''); setAmount(''); setPurpose('');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const load = async () => {
    if (!file) return;
    try {
      const r = await uploadForm<{ stored: number; skipped: { row: number }[] }>('/api/v1/me/shadow-spend/imports', file, {});
      toast(`Принято: ${r.stored}${r.skipped.length ? `, пропущено строк: ${r.skipped.map((s) => s.row).join(', ')}` : ''}`, 'good');
      setFile(null);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const remove = async (id: string) => {
    try { await api('DELETE', `/api/v1/me/shadow-spend/${id}`); reload(); } catch (e) { toast(errorText(e), 'critical'); }
  };
  return (
    <>
      <Card title="Сообщить о своей трате на софт">
        <p className="muted small">Платите за рабочую программу своей картой? Сообщите — без страха и без штрафов. Ваше имя руководство не увидит: в сводке только сервис, отдел и сумма, и только если за этот сервис платят хотя бы трое. Это сигнал компании, что сервис нужен.</p>
        <div className="form-row">
          <Field label="Сервис"><input className="input" value={service} onChange={(e) => setService(e.target.value)} placeholder="ChatGPT Plus" /></Field>
          <Field label="Сумма"><input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Дата оплаты"><input className="input" type="date" value={paidOn} onChange={(e) => setPaidOn(e.target.value)} /></Field>
          <Field label="Для какой работы"><input className="input" value={purpose} onChange={(e) => setPurpose(e.target.value)} /></Field>
        </div>
        <Button variant="primary" disabled={!service.trim() || !amount || !paidOn} onClick={() => { void send(); }}>Сообщить</Button>
        <div className="form-row">
          <Field label="Или несколько из Excel / CSV: сервис, сумма, дата, назначение"><input className="input" type="file" accept=".xlsx,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
        </div>
        <Button disabled={!file} onClick={() => { void load(); }}>Загрузить файл</Button>
      </Card>
      <Card title="Мои сообщения">
        {!data ? <Empty>Загрузка…</Empty> : data.length === 0 ? <Empty>Вы ещё ничего не сообщали</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Сервис</th><th>Категория</th><th className="num">Сумма</th><th>Дата</th><th /></tr></thead>
            <tbody>{data.map((s) => (
              <tr key={s.id}><td>{s.service}{s.purpose && <span className="muted small block">{s.purpose}</span>}</td><td><Badge>{s.category}</Badge></td><td className="num">{money(s.amount, s.currency)}</td><td>{fmtDate(s.paidOn)}</td>
                <td><ConfirmButton variant="ghost" title="Удалить сообщение?" text={s.service} confirmLabel="Удалить" onConfirm={() => { void remove(s.id); }}>Удалить</ConfirmButton></td></tr>
            ))}</tbody>
          </table></div>
        )}
      </Card>
    </>
  );
}

/** owner, finance, it_admin: на что сотрудники тратят свои деньги — без имён. */
export function ShadowSummaryCard() {
  const { data, error } = useApi<Summary>('/api/v1/shadow-spend/summary');
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  return (
    <Card title="Свои траты сотрудников за год">
      <p className="muted small">Сотрудники платят за эти сервисы своими картами — значит, сервис нужен для работы. Показаны сервисы, о которых сообщили не меньше {data.minPeople} человек; имён здесь нет и не будет.{data.hiddenServices > 0 && ` Ещё сервисов с меньшим числом сообщений: ${data.hiddenServices}.`}</p>
      {data.services.length === 0 ? <Empty>Пока нечего показать</Empty> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Сервис</th><th>Категория</th><th className="num">Человек</th><th className="num">Сумма</th><th>Отделы</th></tr></thead>
          <tbody>{data.services.map((s) => (
            <tr key={s.service}><td>{s.service}<span className="muted small block">последняя оплата {s.lastPaid ? fmtDate(s.lastPaid) : '—'}</span></td><td><Badge>{s.category}</Badge></td><td className="num">{s.people}</td><td className="num">{money(s.total, s.currency)}</td>
              <td className="small">{s.departments.map((d) => `${d.department}: ${d.people}`).join('; ')}</td></tr>
          ))}</tbody>
        </table></div>
      )}
    </Card>
  );
}
