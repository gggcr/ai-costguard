// Отпуска (ADR 0053): сотрудник просит, руководитель отдела решает; работодатель видит всё и назначает руководителей.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty, Field, Stat } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface Vacation {
  id: string;
  employeeId: string;
  employeeName: string | null;
  kind: string;
  startsOn: string;
  endsOn: string;
  days: number;
  comment: string | null;
  status: 'pending' | 'approved' | 'rejected' | 'cancelled';
  decidedBy: string | null;
  decisionComment: string | null;
}

interface Balance { year: number; daysPerYear: number; used: number; pending: number; left: number; requests: Vacation[]; hasHead: boolean }

const KIND: Record<string, string> = { annual: 'Ежегодный оплачиваемый', unpaid: 'Без сохранения зарплаты', study: 'Учебный', other: 'Другой' };
const STATUS: Record<Vacation['status'], { label: string; tone: 'warning' | 'good' | 'critical' | 'neutral' }> = {
  pending: { label: 'Ждёт решения', tone: 'warning' },
  approved: { label: 'Согласован', tone: 'good' },
  rejected: { label: 'Не согласован', tone: 'critical' },
  cancelled: { label: 'Отменён', tone: 'neutral' },
};

function days(from: string, to: string): number {
  if (!from || !to) return 0;
  return Math.max(0, Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1);
}

function VacationTable({ items, who, actions }: { items: Vacation[]; who?: boolean; actions?: (v: Vacation) => React.ReactNode }) {
  if (items.length === 0) return <Empty>Заявок нет</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{who && <th>Сотрудник</th>}<th>Даты</th><th className="num">Дней</th><th>Вид</th><th>Статус</th><th /></tr></thead>
        <tbody>
          {items.map((v) => (
            <tr key={v.id}>
              {who && <td>{v.employeeName}</td>}
              <td>{fmtDate(v.startsOn)} — {fmtDate(v.endsOn)}{v.comment && <span className="muted small block">{v.comment}</span>}</td>
              <td className="num">{v.days}</td>
              <td>{KIND[v.kind] ?? v.kind}</td>
              <td><Badge tone={STATUS[v.status].tone}>{STATUS[v.status].label}</Badge>{v.decisionComment && <span className="muted small block">{v.decisionComment}</span>}</td>
              <td className="row-gap">{actions?.(v)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Decide({ path, onDone }: { path: string; onDone: () => void }) {
  const { toast } = useStore();
  const send = async (approve: boolean) => {
    const comment = approve ? '' : window.prompt('Причина отказа (необязательно)') ?? '';
    try {
      await api('PUT', path, { approve, comment });
      onDone();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return <><Button variant="primary" onClick={() => { void send(true); }}>Согласовать</Button><Button variant="ghost" onClick={() => { void send(false); }}>Отказать</Button></>;
}

export function MyVacations() {
  const { toast } = useStore();
  const year = new Date().getFullYear();
  const [shownYear, setShownYear] = useState(year);
  const mine = useApi<Balance>(`/api/v1/me/vacations?year=${shownYear}`);
  const team = useApi<Vacation[]>('/api/v1/me/team/vacations');
  const [kind, setKind] = useState('annual');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [comment, setComment] = useState('');
  const n = days(from, to);
  if (mine.error) return <Empty>{mine.error}</Empty>;
  if (!mine.data) return <Empty>Загрузка…</Empty>;
  const b = mine.data;
  const ask = async () => {
    try {
      await api('POST', '/api/v1/me/vacations', { kind, startsOn: from, endsOn: to, comment });
      toast(b.hasHead ? 'Заявка отправлена руководителю отдела' : 'Заявка отправлена', 'good');
      setFrom(''); setTo(''); setComment('');
      mine.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const cancel = async (id: string) => {
    try {
      await api('DELETE', `/api/v1/me/vacations/${id}`);
      mine.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const today = new Date().toISOString().slice(0, 10);
  return (
    <>
      <div className="stats">
        <Stat label={`Осталось дней в ${b.year}`} value={b.left} hint={`из ${b.daysPerYear} календарных`} />
        <Stat label="Согласовано" value={b.used} />
        <Stat label="Ждут решения" value={b.pending} />
      </div>
      <Card title="Новая заявка" actions={<select className="input" value={shownYear} onChange={(e) => setShownYear(Number(e.target.value))}>{[year, year + 1].map((y) => <option key={y} value={y}>{y}</option>)}</select>}>
        {!b.hasHead && <p className="muted small">У вашего отдела не назначен руководитель: заявку рассмотрит работодатель.</p>}
        <div className="form-row">
          <Field label="Вид отпуска"><select className="input" value={kind} onChange={(e) => setKind(e.target.value)}>{Object.entries(KIND).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></Field>
          <Field label="С"><input className="input" type="date" min={today} value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="По"><input className="input" type="date" min={from || today} value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label="Комментарий"><input className="input" value={comment} onChange={(e) => setComment(e.target.value)} /></Field>
        </div>
        <Button variant="primary" disabled={!from || !to || n === 0} onClick={() => { void ask(); }}>Отправить заявку{n ? ` на ${n} дн.` : ''}</Button>
      </Card>
      <Card title="Мои заявки">
        <VacationTable items={b.requests} actions={(v) => {
          const cancellable = v.status === 'pending' || (v.status === 'approved' && v.startsOn > today);
          return cancellable && <ConfirmButton variant="ghost" title="Отменить отпуск?" text={`${fmtDate(v.startsOn)} — ${fmtDate(v.endsOn)}`} confirmLabel="Отменить" onConfirm={() => { void cancel(v.id); }}>Отменить</ConfirmButton>;
        }} />
      </Card>
      {team.data && team.data.length > 0 && (
        <Card title="Заявки моего отдела">
          <VacationTable who items={team.data} actions={(v) => v.status === 'pending' && <Decide path={`/api/v1/me/team/vacations/${v.id}/decision`} onDone={team.reload} />} />
        </Card>
      )}
    </>
  );
}

/** Работодатель (owner, finance, it_admin): руководители отделов и все заявки. */
export function EmployerVacations({ canManageHeads }: { canManageHeads: boolean }) {
  const { state, toast, actions } = useStore();
  const all = useApi<Vacation[]>('/api/v1/vacations');
  const setHead = async (departmentId: string, employeeId: string) => {
    try {
      await api('PUT', `/api/v1/departments/${departmentId}/head`, { employeeId: employeeId || null });
      await actions.reset();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <>
      <Card title="Руководители отделов" actions={<span className="muted small">Руководитель согласует отпуска своего отдела</span>}>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Отдел</th><th>Руководитель</th></tr></thead>
            <tbody>
              {state.departments.map((d) => (
                <tr key={d.id}>
                  <td>{d.name}</td>
                  <td>
                    <select className="input" disabled={!canManageHeads} value={d.headEmployeeId ?? ''} onChange={(e) => { void setHead(d.id, e.target.value); }}>
                      <option value="">— не назначен —</option>
                      {state.employees.filter((e) => e.active).map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      <Card title="Заявки на отпуск">
        {all.error ? <Empty>{all.error}</Empty> : !all.data ? <Empty>Загрузка…</Empty> : (
          <VacationTable who items={all.data} actions={(v) => v.status === 'pending' && <Decide path={`/api/v1/vacations/${v.id}/decision`} onDone={all.reload} />} />
        )}
      </Card>
    </>
  );
}
