// Бюджет-светофор и антифрод для сотрудника (ADR 0067): зелёный — в лимите, жёлтый — 80% или детектор насторожился,
// красный — шлюз остановил агента. «Продлить лимит» уходит руководителю отдела (или финансисту). Операцию, которую
// шлюз придержал, владелец подтверждает (подтверждение записывается в NEAR) или блокирует.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, Empty, Field, Modal, Progress } from '../../components/ui';
import { currentCurrency, fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

type Light = 'green' | 'yellow' | 'red';
interface Waiting { id: string; description: string; amount: number; reason: 'above_threshold' | 'risk' | string; createdAt: string; deadline: string }
interface LimitRequest {
  id: string; employeeName: string; agentId: string; agentName: string | null; currentLimit: number; spent: number; add: number;
  currency: string; reason: string; status: 'pending' | 'approved' | 'rejected' | 'cancelled'; decidedBy: string | null;
  decisionComment: string | null; createdAt: string;
}
interface Agent {
  id: string; name: string; status: string; riskAction: string; riskScore: number; light: Light; why: string;
  limit: number | null; spent: number | null; percent: number | null; currency: string | null;
  limitRequest: LimitRequest | null; waiting: Waiting[];
}
interface Mine { agents: Agent[]; requests: LimitRequest[] }

const LIGHT_LABEL: Record<Light, string> = { green: 'В лимите', yellow: 'Внимание', red: 'Остановлен' };
const STATUS: Record<LimitRequest['status'], { label: string; tone: 'good' | 'warning' | 'critical' | 'neutral' }> = {
  pending: { label: 'Ждёт решения', tone: 'warning' },
  approved: { label: 'Продлён', tone: 'good' },
  rejected: { label: 'Отказано', tone: 'critical' },
  cancelled: { label: 'Отменён', tone: 'neutral' },
};

const money = (n: number, currency: string | null) => new Intl.NumberFormat('ru-RU', { style: 'currency', currency: currency ?? currentCurrency(), maximumFractionDigits: 2 }).format(n);

function TrafficLight({ light }: { light: Light }) {
  return (
    <span className="traffic" role="img" aria-label={LIGHT_LABEL[light]}>
      {(['red', 'yellow', 'green'] as Light[]).map((l) => <span key={l} className={`traffic-lamp traffic-${l} ${l === light ? 'on' : ''}`} />)}
    </span>
  );
}

function LimitModal({ agent, onClose, onDone }: { agent: Agent; onClose: () => void; onDone: () => void }) {
  const { toast } = useStore();
  const suggested = Math.max(1, Math.round((agent.limit ?? 0) * 0.5));
  const [add, setAdd] = useState(String(suggested));
  const [reason, setReason] = useState('');
  const send = async () => {
    try {
      await api('POST', `/api/v1/me/agents/${agent.id}/limit-requests`, { add: Number(add.replace(',', '.')), reason });
      toast('Запрос на продление лимита отправлен руководителю', 'good');
      onDone();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <Modal open onClose={onClose} title={`Продлить лимит «${agent.name}»`} footer={<><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!reason.trim() || !(Number(add.replace(',', '.')) > 0)} onClick={() => { void send(); }}>Отправить</Button></>}>
      <Field label="На сколько увеличить" hint={agent.limit !== null ? `Сейчас ${money(agent.limit, agent.currency)}, потрачено ${money(agent.spent ?? 0, agent.currency)}` : undefined}>
        <input className="input" inputMode="decimal" value={add} onChange={(e) => setAdd(e.target.value)} />
      </Field>
      <Field label="Зачем" hint="Коротко: руководитель увидит это в уведомлении">
        <input className="input" value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} placeholder="Например: квартальный отчёт, нужно ещё 2 дня работы" />
      </Field>
    </Modal>
  );
}

export function MyAgents() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<Mine>('/api/v1/me/agents');
  const team = useApi<LimitRequest[]>('/api/v1/me/team/limit-requests');
  const [raising, setRaising] = useState<Agent | null>(null);
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const decide = async (w: Waiting, approve: boolean) => {
    const comment = approve ? '' : window.prompt('Почему блокируете (необязательно)') ?? '';
    try {
      const r = await api<{ status: string; notaryHash: string | null }>('PUT', `/api/v1/me/agents/operations/${w.id}/decision`, { approve, comment });
      toast(approve ? `Операция подтверждена${r.notaryHash ? ', подтверждение записывается в NEAR' : ''}` : 'Операция заблокирована', approve ? 'good' : 'info');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const cancel = async (id: string) => {
    try {
      await api('DELETE', `/api/v1/me/limit-requests/${id}`);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const teamDecide = async (r: LimitRequest, approve: boolean) => {
    const comment = approve ? '' : window.prompt('Причина отказа (необязательно)') ?? '';
    try {
      await api('PUT', `/api/v1/me/team/limit-requests/${r.id}/decision`, { approve, comment });
      team.reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <>
      {raising && <LimitModal agent={raising} onClose={() => setRaising(null)} onDone={() => { setRaising(null); reload(); }} />}
      <Card title="Мои агенты">
        {data.agents.length === 0 ? <Empty>За вами не закреплено ни одного ИИ-агента</Empty> : (
          <ul className="list">
            {data.agents.map((a) => (
              <li key={a.id} className="agent-light">
                <div className="list-row">
                  <TrafficLight light={a.light} />
                  <span className="grow"><b>{a.name}</b><span className="muted small block">{a.why}</span></span>
                  {a.limitRequest ? <Badge tone="warning">{`Просили +${money(a.limitRequest.add, a.currency)}`}</Badge>
                    : a.limit !== null && <Button variant={a.light === 'green' ? 'ghost' : 'primary'} onClick={() => setRaising(a)}>Продлить лимит</Button>}
                </div>
                {a.limit !== null && (
                  <div className="agent-budget">
                    <Progress value={a.spent ?? 0} max={a.limit} label={`Бюджет агента ${a.name}`} />
                    <span className="muted small">{money(a.spent ?? 0, a.currency)} из {money(a.limit, a.currency)}</span>
                  </div>
                )}
                {a.waiting.map((w) => (
                  <div key={w.id} className="alert alert-warning list-row">
                    <span className="grow">
                      <b>{money(w.amount, a.currency)}</b> · {w.description}
                      <span className="small block">{w.reason === 'risk' ? 'Детектор аномалий счёл операцию необычной: незнакомый сервис или сумма больше обычного' : 'Сумма выше порога мандата'} · решить до {fmtDate(w.deadline)}</span>
                    </span>
                    <Button variant="primary" onClick={() => { void decide(w, true); }}>Подтвердить</Button>
                    <Button variant="ghost" onClick={() => { void decide(w, false); }}>Заблокировать</Button>
                  </div>
                ))}
              </li>
            ))}
          </ul>
        )}
        <p className="muted small">Зелёный — в лимите, жёлтый — потрачено 80% или нужна ваша проверка, красный — шлюз остановил агента. Подтверждения операций записываются в NEAR.</p>
      </Card>
      {data.requests.length > 0 && (
        <Card title="Мои запросы на лимит">
          <ul className="list">
            {data.requests.map((r) => (
              <li key={r.id} className="list-row">
                <span className="grow">{r.agentName ?? 'Агент'}: +{money(r.add, r.currency)}<span className="muted small block">{fmtDate(r.createdAt)} · {r.reason}{r.decisionComment ? ` · ${r.decisionComment}` : ''}</span></span>
                <Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge>
                {r.status === 'pending' && <Button variant="ghost" onClick={() => { void cancel(r.id); }}>Отменить</Button>}
              </li>
            ))}
          </ul>
        </Card>
      )}
      {team.data && team.data.length > 0 && (
        <Card title="Лимиты агентов моего отдела">
          <LimitTable rows={team.data} onDecide={(r, ok) => { void teamDecide(r, ok); }} />
        </Card>
      )}
    </>
  );
}

function LimitTable({ rows, onDecide }: { rows: LimitRequest[]; onDecide?: (r: LimitRequest, approve: boolean) => void }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Сотрудник</th><th>Агент</th><th className="num">Лимит</th><th className="num">Просит</th><th>Статус</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.employeeName}<span className="muted small block">{r.reason}</span></td>
              <td>{r.agentName ?? '—'}</td>
              <td className="num">{money(r.currentLimit, r.currency)}<span className="muted small block">{`потрачено ${money(r.spent, r.currency)}`}</span></td>
              <td className="num">+{money(r.add, r.currency)}</td>
              <td><Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge>{r.decisionComment && <span className="muted small block">{r.decisionComment}</span>}</td>
              <td className="row-gap">{onDecide && r.status === 'pending' && <><Button variant="primary" onClick={() => onDecide(r, true)}>Продлить</Button><Button variant="ghost" onClick={() => onDecide(r, false)}>Отказать</Button></>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** owner и finance: все запросы на продление лимита, решают там, где у отдела нет руководителя. */
export function EmployerLimitRequests() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<LimitRequest[]>('/api/v1/limit-requests');
  const decide = async (r: LimitRequest, approve: boolean) => {
    const comment = approve ? '' : window.prompt('Причина отказа (необязательно)') ?? '';
    try {
      await api('PUT', `/api/v1/limit-requests/${r.id}/decision`, { approve, comment });
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <Card title="Запросы на продление лимита агентов">
      {error ? <Empty>{error}</Empty> : !data ? <Empty>Загрузка…</Empty> : data.length === 0 ? <Empty>Запросов нет</Empty>
        : <LimitTable rows={data} onDecide={(r, ok) => { void decide(r, ok); }} />}
    </Card>
  );
}
