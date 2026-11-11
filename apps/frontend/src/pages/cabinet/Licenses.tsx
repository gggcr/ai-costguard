// Пул лицензий (ADR 0055): свободное место выдаётся сразу; простаивающая у коллеги лицензия возвращается в пул
// решением ИТ. Лицензии переназначаются, а не одалживаются.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface Held { licenseId: string; subscriptionId: string; vendor: string; product: string; lastActive: string | null }
interface Offer { subscriptionId: string; vendor: string; product: string; seats: number; assigned: number; free: number; idle: number }
interface LicenseRequest {
  id: string; employeeName: string; subscriptionId: string; product: string; vendor: string; reason: string | null;
  status: 'granted' | 'pending' | 'approved' | 'rejected' | 'cancelled'; takesIdle: boolean; idleSince: string | null;
  decisionComment: string | null; createdAt: string;
}
interface Mine { held: Held[]; pool: Offer[]; requests: LicenseRequest[]; idleDays: number }

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
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const held = new Set(data.held.map((h) => h.subscriptionId));
  const asked = new Set(data.requests.filter((r) => r.status === 'pending').map((r) => r.subscriptionId));
  const ask = async (o: Offer) => {
    try {
      const r = await api<LicenseRequest>('POST', '/api/v1/me/license-requests', { subscriptionId: o.subscriptionId, reason });
      toast(r.status === 'granted' ? `Лицензия ${o.product} выдана` : r.takesIdle ? 'Запрос у ИТ: простаивающую лицензию вернут в пул и выдадут вам' : 'Запрос отправлен в ИТ', r.status === 'granted' ? 'good' : 'info');
      setReason('');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
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
            {data.held.map((h) => (
              <li key={h.licenseId} className="list-row"><span className="grow"><b>{h.product}</b><span className="muted small block">{h.vendor} · последний вход: {h.lastActive ? fmtDate(h.lastActive) : 'нет данных'}</span></span></li>
            ))}
          </ul>
        )}
        <p className="muted small">Лицензия, которой не пользуются {data.idleDays} дней, может вернуться в общий пул для коллеги.</p>
      </Card>
      <Card title="Общий пул компании">
        <div className="form-row"><input className="input" placeholder="Зачем нужна программа (необязательно)" value={reason} onChange={(e) => setReason(e.target.value)} /></div>
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
                <span className="grow">{r.product}<span className="muted small block">{fmtDate(r.createdAt)}{r.decisionComment ? ` · ${r.decisionComment}` : ''}</span></span>
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
  const decide = async (r: LicenseRequest, approve: boolean) => {
    const comment = approve ? '' : window.prompt('Причина отказа (необязательно)') ?? '';
    try {
      await api('PUT', `/api/v1/license-requests/${r.id}/decision`, { approve, comment });
      requests.reload(); pool.reload();
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
                    <td>{r.employeeName}{r.reason && <span className="muted small block">{r.reason}</span>}</td>
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
      <Card title="Пул по подпискам" actions={canDecide && <ConfirmButton title="Вернуть простаивающие лицензии в пул?" text="Владельцы получат уведомление и смогут запросить лицензию снова." confirmLabel="Вернуть" onConfirm={() => { void reclaim(); }}>Вернуть простаивающие в пул</ConfirmButton>}>
        {!pool.data ? <Empty>Загрузка…</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Программа</th><th className="num">Мест</th><th className="num">Выдано</th><th className="num">Свободно</th><th className="num">Простаивает</th></tr></thead>
              <tbody>{pool.data.map((o) => <tr key={o.subscriptionId}><td>{o.product}<span className="muted small block">{o.vendor}</span></td><td className="num">{o.seats}</td><td className="num">{o.assigned}</td><td className="num">{o.free}</td><td className="num">{o.idle}</td></tr>)}</tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
