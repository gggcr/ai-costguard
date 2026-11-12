// Пул лицензий (ADR 0055): свободное место выдаётся сразу; простаивающая у коллеги лицензия возвращается в пул
// решением ИТ. Лицензию можно взять на срок (ADR 0066): по окончании она вернётся в пул или коллеге, у которого
// её взяли. Перед автопродлением подписки неиспользуемую лицензию предлагают вернуть.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';
import { AccessModal } from './Access';

interface Held { licenseId: string; subscriptionId: string; vendor: string; product: string; lastActive: string | null; renewalDate: string }
interface Lease { licenseId: string; product: string; endsOn: string; fromColleague: boolean }
interface Offer { subscriptionId: string; vendor: string; product: string; seats: number; assigned: number; free: number; idle: number }
interface LicenseRequest {
  id: string; employeeName: string; subscriptionId: string; product: string; vendor: string; reason: string | null;
  status: 'granted' | 'pending' | 'approved' | 'rejected' | 'cancelled'; takesIdle: boolean; idleSince: string | null;
  decisionComment: string | null; createdAt: string; days: number | null;
}
interface Mine { held: Held[]; pool: Offer[]; requests: LicenseRequest[]; idleDays: number; borrowed: Lease[]; lent: Lease[] }

const TERMS: { value: string; label: string }[] = [
  { value: '', label: 'насовсем' }, { value: '7', label: 'на неделю' }, { value: '14', label: 'на 2 недели' }, { value: '30', label: 'на месяц' },
];

function idleFor(h: Held, idleDays: number): boolean {
  return !h.lastActive || (Date.now() - new Date(h.lastActive).getTime()) / 86_400_000 > idleDays;
}

const STATUS: Record<LicenseRequest['status'], { label: string; tone: 'good' | 'warning' | 'critical' | 'neutral' }> = {
  granted: { label: 'Выдана из пула', tone: 'good' },
  pending: { label: 'Ждёт решения ИТ', tone: 'warning' },
  approved: { label: 'Выдана', tone: 'good' },
  rejected: { label: 'Отказано', tone: 'critical' },
  cancelled: { label: 'Отменена', tone: 'neutral' },
};

function availability(o: Offer): string {
  if (o.free > 0) return `свободно: ${o.free}`;
  if (o.idle > 0) return `простаивает: ${o.idle}`;
  return 'свободных нет';
}

export function MyLicenses() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<Mine>('/api/v1/me/licenses');
  const [reason, setReason] = useState('');
  const [term, setTerm] = useState('');
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const held = new Set(data.held.map((h) => h.subscriptionId));
  const asked = new Set(data.requests.filter((r) => r.status === 'pending').map((r) => r.subscriptionId));
  const ask = async (o: Offer) => {
    try {
      const r = await api<LicenseRequest>('POST', '/api/v1/me/license-requests', { subscriptionId: o.subscriptionId, reason, days: term ? Number(term) : null });
      toast(r.status === 'granted' ? `Лицензия ${o.product} выдана` : r.takesIdle ? 'Запрос у ИТ: простаивающую лицензию вернут в пул и выдадут вам' : 'Запрос отправлен в ИТ', r.status === 'granted' ? 'good' : 'info');
      setReason('');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const release = async (h: Held) => {
    try {
      await api('POST', `/api/v1/me/licenses/${h.licenseId}/release`);
      toast(`Лицензия ${h.product} возвращена`, 'good');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const lease = new Map(data.borrowed.map((l) => [l.licenseId, l]));
  const cancel = async (id: string) => {
    try {
      await api('DELETE', `/api/v1/me/license-requests/${id}`);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <>
      <Card title="Мои программы">
        {data.held.length === 0 ? <Empty>Лицензий нет</Empty> : (
          <ul className="list">
            {data.held.map((h) => {
              const l = lease.get(h.licenseId);
              return (
                <li key={h.licenseId} className="list-row">
                  <span className="grow"><b>{h.product}</b><span className="muted small block">{h.vendor} · последний вход: {h.lastActive ? fmtDate(h.lastActive) : 'нет данных'} · продление {fmtDate(h.renewalDate)}</span></span>
                  {l ? <Badge tone="info">{`до ${fmtDate(l.endsOn)}, потом ${l.fromColleague ? 'вернётся коллеге' : 'в пул'}`}</Badge>
                    : idleFor(h, data.idleDays) && <Badge tone="warning">не используется</Badge>}
                  <ConfirmButton variant="ghost" title={`Вернуть ${h.product}?`} text={l?.fromColleague ? 'Лицензия сразу вернётся коллеге, у которого её взяли.' : 'Место вернётся в общий пул: его получит коллега или компания не будет за него платить.'} confirmLabel="Вернуть" onConfirm={() => { void release(h); }}>Вернуть</ConfirmButton>
                </li>
              );
            })}
          </ul>
        )}
        <p className="muted small">Лицензия, которой не пользуются {data.idleDays} дней, может вернуться в общий пул для коллеги. За неделю до автопродления подписки придёт напоминание.</p>
      </Card>
      {data.lent.length > 0 && (
        <Card title="Одолжены коллегам">
          <ul className="list">
            {data.lent.map((l) => <li key={l.licenseId} className="list-row"><span className="grow">{l.product}</span><Badge tone="info">{`вернётся ${fmtDate(l.endsOn)}`}</Badge></li>)}
          </ul>
        </Card>
      )}
      <Card title="Общий пул компании">
        <div className="form-row">
          <input className="input" placeholder="Зачем нужна программа (необязательно)" value={reason} onChange={(e) => setReason(e.target.value)} />
          <select className="input" aria-label="Срок" value={term} onChange={(e) => setTerm(e.target.value)}>{TERMS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}</select>
        </div>
        {data.pool.length === 0 ? <Empty>Подписок пока нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Программа</th><th>Наличие</th><th /></tr></thead>
              <tbody>
                {data.pool.map((o) => (
                  <tr key={o.subscriptionId}>
                    <td>{o.product}<span className="muted small block">{o.vendor}</span></td>
                    <td>{availability(o)}</td>
                    <td>{held.has(o.subscriptionId) ? <Badge tone="good">У вас есть</Badge>
                      : asked.has(o.subscriptionId) ? <Badge tone="warning">Запрошена</Badge>
                      : <Button variant={o.free > 0 ? 'primary' : 'secondary'} onClick={() => { void ask(o); }}>{o.free > 0 ? 'Получить' : 'Запросить'}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {data.requests.length > 0 && (
        <Card title="Мои запросы">
          <ul className="list">
            {data.requests.map((r) => (
              <li key={r.id} className="list-row">
                <span className="grow">{r.product}{r.days ? ` · на ${r.days} дн.` : ''}<span className="muted small block">{fmtDate(r.createdAt)}{r.decisionComment ? ` · ${r.decisionComment}` : ''}</span></span>
                <Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge>
                {r.status === 'pending' && <ConfirmButton variant="ghost" title="Отменить запрос?" text={r.product} confirmLabel="Отменить" onConfirm={() => { void cancel(r.id); }}>Отменить</ConfirmButton>}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </>
  );
}

/** ИТ (owner, it_admin): запросы, наличие по подпискам, возврат простаивающих в пул. finance только смотрит. */
export function EmployerLicenses({ canDecide }: { canDecide: boolean }) {
  const { toast } = useStore();
  const requests = useApi<LicenseRequest[]>('/api/v1/license-requests');
  const pool = useApi<Offer[]>('/api/v1/license-pool');
  const [accessOf, setAccessOf] = useState<Offer | null>(null);
  const decide = async (r: LicenseRequest, approve: boolean) => {
    const comment = approve ? '' : window.prompt('Причина отказа (необязательно)') ?? '';
    try {
      await api('PUT', `/api/v1/license-requests/${r.id}/decision`, { approve, comment });
      requests.reload(); pool.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const activity = async () => {
    try {
      const r = await api<{ signIns: number; licensesUpdated: number; unmatchedApps: string[] }>('POST', '/api/v1/license-pool/microsoft-activity');
      toast(`Входов Microsoft: ${r.signIns}, отмечено используемых лицензий: ${r.licensesUpdated}${r.unmatchedApps.length ? `. Нет подписки для: ${r.unmatchedApps.slice(0, 5).join(', ')}` : ''}`, 'good');
      pool.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const reclaim = async () => {
    try {
      const r = await api<{ licenses: number }>('POST', '/api/v1/license-pool/reclaim');
      toast(`Возвращено в пул: ${r.licenses}`, 'good');
      pool.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <>
      <Card title="Запросы лицензий">
        {requests.error ? <Empty>{requests.error}</Empty> : !requests.data ? <Empty>Загрузка…</Empty> : requests.data.length === 0 ? <Empty>Запросов нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Сотрудник</th><th>Программа</th><th>Статус</th><th /></tr></thead>
              <tbody>
                {requests.data.map((r) => (
                  <tr key={r.id}>
                    <td>{r.employeeName}{r.reason && <span className="muted small block">{r.reason}</span>}{r.days && <span className="muted small block">{`на ${r.days} дн.`}</span>}</td>
                    <td>{r.product}{r.takesIdle && r.status === 'pending' && <span className="muted small block">заберёт простаивающую{r.idleSince ? ` с ${fmtDate(r.idleSince)}` : ''}</span>}</td>
                    <td><Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge></td>
                    <td className="row-gap">{canDecide && r.status === 'pending' && <><Button variant="primary" onClick={() => { void decide(r, true); }}>Выдать</Button><Button variant="ghost" onClick={() => { void decide(r, false); }}>Отказать</Button></>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {accessOf && <AccessModal subscriptionId={accessOf.subscriptionId} product={accessOf.product} onClose={() => setAccessOf(null)} />}
      <Card title="Пул по подпискам" actions={canDecide && <><Button variant="ghost" onClick={() => { void activity(); }}>Активность из Microsoft 365</Button><ConfirmButton title="Вернуть простаивающие лицензии в пул?" text="Владельцы получат уведомление и смогут запросить лицензию снова." confirmLabel="Вернуть" onConfirm={() => { void reclaim(); }}>Вернуть простаивающие в пул</ConfirmButton></>}>
        {!pool.data ? <Empty>Загрузка…</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Программа</th><th className="num">Мест</th><th className="num">Выдано</th><th className="num">Свободно</th><th className="num">Простаивает</th>{canDecide && <th />}</tr></thead>
              <tbody>{pool.data.map((o) => <tr key={o.subscriptionId}><td>{o.product}<span className="muted small block">{o.vendor}</span></td><td className="num">{o.seats}</td><td className="num">{o.assigned}</td><td className="num">{o.free}</td><td className="num">{o.idle}</td>{canDecide && <td><Button variant="ghost" onClick={() => setAccessOf(o)}>Доступ у поставщика</Button></td>}</tr>)}</tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
