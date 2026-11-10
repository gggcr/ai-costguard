import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { Badge, Button, Card, CodeBadge, ConfirmButton, Empty, Field, Modal, PageHeader, Progress, RiskBadge, Stat } from '../components/ui';
import { fmtDate, fmtDateTime, fmtRub, short } from '../lib';
import type { Agent } from '../api/types';

export function Agents() {
  const { state } = useStore();
  const { canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<'all' | 'active' | 'suspended'>('all');
  const [dept, setDept] = useState('all');
  const [creating, setCreating] = useState(false);

  const list = state.agents.filter((a) =>
    (status === 'all' || a.status === status) && (dept === 'all' || a.departmentId === dept) && a.name.toLowerCase().includes(q.toLowerCase()));

  return (
    <>
      <PageHeader title="ИИ-агенты" subtitle="Каждый агент ходит к провайдерам только через шлюз CostGuard со своим виртуальным ключом"
        actions={canEdit('agents') && <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>Подключить агента</Button>} />
      <div className="filters">
        <input className="input" placeholder="Название агента" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Фильтр по названию" />
        <select className="input" value={status} onChange={(e) => setStatus(e.target.value as typeof status)} aria-label="Статус">
          <option value="all">Все статусы</option><option value="active">Работают</option><option value="suspended">Остановлены</option>
        </select>
        <select className="input" value={dept} onChange={(e) => setDept(e.target.value)} aria-label="Отдел">
          <option value="all">Все отделы</option>
          {state.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </div>
      <Card>
        {list.length === 0 ? <Empty>Агентов по фильтру нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Агент</th><th>Отдел</th><th>Владелец</th><th>Мандат</th><th>Бюджет на месяц</th><th>Статус</th><th className="num">Риск</th></tr></thead>
              <tbody>
                {list.map((a) => {
                  const b = L.budgetOfAgent(a.id);
                  return (
                    <tr key={a.id} className="clickable" onClick={() => navigate(`/agents/${a.id}`)}>
                      <td><Link to={`/agents/${a.id}`} onClick={(e) => e.stopPropagation()}>{a.name}</Link><span className="muted small block">{a.framework}</span></td>
                      <td>{L.dept(a.departmentId)?.name}</td>
                      <td>{L.employee(a.ownerId)?.name}</td>
                      <td>{L.mandate(a.mandateId)?.name}</td>
                      <td style={{ minWidth: 160 }}>{b ? <>{fmtRub(b.spent)} <span className="muted small">из {fmtRub(b.limit)}</span><Progress value={b.spent} max={b.limit} /></> : '—'}</td>
                      <td>{a.status === 'active' ? <Badge tone="good">Работает</Badge> : <Badge tone="critical">Остановлен</Badge>}</td>
                      <td className="num"><RiskBadge score={a.riskScore} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <AgentForm open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function AgentForm({ open, onClose, agent }: { open: boolean; onClose: () => void; agent?: Agent }) {
  const { state, actions } = useStore();
  const navigate = useNavigate();
  const [name, setName] = useState(agent?.name ?? '');
  const [description, setDescription] = useState(agent?.description ?? '');
  const [framework, setFramework] = useState(agent?.framework ?? 'LangChain');
  const [departmentId, setDepartmentId] = useState(agent?.departmentId ?? state.departments[0]?.id ?? '');
  const [ownerId, setOwnerId] = useState(agent?.ownerId ?? state.employees[0]?.id ?? '');
  const [mandateId, setMandateId] = useState(agent?.mandateId ?? state.mandates.find((m) => m.status === 'published')?.id ?? '');
  const [limit, setLimit] = useState(30000);

  const submit = async () => {
    if (!name.trim()) return;
    if (agent) {
      await actions.updateAgent(agent.id, { name, description, framework, departmentId, ownerId, mandateId });
      onClose();
    } else {
      const id = await actions.createAgent({ name, description, framework, departmentId, ownerId, mandateId }, limit);
      if (!id) return;
      onClose();
      navigate(`/agents/${id}`);
    }
  };

  return (
    <Modal open={open} onClose={onClose} title={agent ? 'Изменить агента' : 'Подключить агента'} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" onClick={submit} disabled={!name.trim()}>{agent ? 'Сохранить' : 'Подключить'}</Button></>
    }>
      <div className="form">
        <Field label="Название"><input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Например, Бот закупок" /></Field>
        <Field label="Что делает"><textarea className="input" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        <div className="form-row">
          <Field label="Фреймворк">
            <select className="input" value={framework} onChange={(e) => setFramework(e.target.value)}>
              {['LangChain', 'CrewAI', 'AutoGen', 'Claude Agent SDK', 'OpenAI Agents SDK', 'n8n (no-code)', 'Другое'].map((f) => <option key={f}>{f}</option>)}
            </select>
          </Field>
          <Field label="Отдел">
            <select className="input" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              {state.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
        </div>
        <div className="form-row">
          <Field label="Владелец">
            <select className="input" value={ownerId} onChange={(e) => setOwnerId(e.target.value)}>
              {state.employees.filter((e) => e.active).map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select>
          </Field>
          <Field label="Мандат" hint="Что агенту разрешено: модели, поставщики, суммы">
            <select className="input" value={mandateId} onChange={(e) => setMandateId(e.target.value)}>
              {state.mandates.map((m) => <option key={m.id} value={m.id}>{m.name}{m.status === 'draft' ? ' (черновик)' : ''}</option>)}
            </select>
          </Field>
        </div>
        {!agent && <Field label="Бюджет на месяц, ₽"><input className="input" type="number" min={0} step={1000} value={limit} onChange={(e) => setLimit(Number(e.target.value))} /></Field>}
      </div>
    </Modal>
  );
}

export function AgentDetail() {
  const { id } = useParams();
  const { state, actions } = useStore();
  const { user, canManageAgent } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [newKey, setNewKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const agent = L.agent(id);
  const ops = useMemo(() => state.operations.filter((o) => o.agentId === id), [state.operations, id]);

  if (!agent) return <Empty action={<Link to="/agents" className="btn btn-primary">К списку агентов</Link>}>Агент не найден</Empty>;

  const manage = canManageAgent(agent);
  const budget = L.budgetOfAgent(agent.id);
  const mandate = L.mandate(agent.mandateId);
  const keys = state.keys.filter((k) => k.agentId === agent.id);
  const allowed = ops.filter((o) => o.code === 'allowed');
  const denied = ops.length - allowed.length;

  return (
    <>
      <PageHeader back={{ to: '/agents', label: 'Агенты' }} title={agent.name} subtitle={agent.description}
        actions={manage && (
          <>
            <Button icon="policies" onClick={() => setEditing(true)}>Изменить</Button>
            {agent.status === 'active'
              ? <ConfirmButton variant="danger" icon="pause" title="Остановить агента?" text="Шлюз начнёт отвечать 423 agent_suspended на все запросы агента. Резервы вернутся в бюджет." confirmLabel="Остановить" onConfirm={() => actions.suspendAgent(agent.id, user?.name ?? '')}>Остановить</ConfirmButton>
              : <Button variant="primary" icon="play" onClick={() => actions.resumeAgent(agent.id)}>Запустить снова</Button>}
          </>
        )} />

      {agent.status === 'suspended' && <div className="alert alert-critical"><strong>Агент остановлен.</strong> {agent.riskReason}</div>}

      <div className="stats">
        <Stat label="Статус" value={agent.status === 'active' ? <Badge tone="good">Работает</Badge> : <Badge tone="critical">Остановлен</Badge>} hint={`${agent.framework} · с ${fmtDate(agent.createdAt)}`} />
        <Stat label="Риск-оценка детектора" value={<RiskBadge score={agent.riskScore} />} hint={agent.riskReason} />
        <Stat label="Потрачено за период бюджета" value={budget ? fmtRub(budget.spent) : '—'} hint={budget && <>из {fmtRub(budget.limit)} <Progress value={budget.spent} max={budget.limit} /></>} to="/budgets" />
        <Stat label="Операций за 14 дней" value={ops.length} hint={`${denied} отказов шлюза`} />
      </div>

      <div className="grid-2">
        <Card title="Карточка">
          <dl className="dl">
            <dt>Отдел</dt><dd>{L.dept(agent.departmentId)?.name}</dd>
            <dt>Владелец</dt><dd>{L.employee(agent.ownerId)?.name}</dd>
            <dt>Мандат</dt><dd>{mandate ? <Link to={`/policies?open=${mandate.id}`}>{mandate.name}</Link> : '—'} {mandate?.status === 'draft' && <Badge tone="warning">черновик</Badge>}</dd>
            <dt>Лимит на операцию</dt><dd>{mandate ? fmtRub(mandate.maxPerOperation) : '—'}</dd>
            <dt>Порог согласования</dt><dd>{mandate ? `выше ${fmtRub(mandate.approvalThreshold)} решает человек` : '—'}</dd>
            <dt>Разрешённые модели</dt><dd>{mandate?.models.join(', ')}</dd>
          </dl>
        </Card>

        <Card title="Виртуальные ключи" actions={manage && <Button icon="key" onClick={async () => { const key = await actions.issueKey(agent.id); if (key) { setNewKey(key); setCopied(false); } }}>Выпустить ключ</Button>}>
          <p className="muted small">Агент передаёт ключ шлюзу вместо ключей провайдеров. Настоящие ключи хранятся в Vault.</p>
          <ul className="list">
            {keys.map((k) => (
              <li key={k.id} className="list-row">
                <span><code>{k.prefix}…</code><span className="muted small block">выпущен {fmtDate(k.createdAt)}</span></span>
                <span className="row-gap">
                  {k.status === 'active' ? <Badge tone="good">Активен</Badge> : <Badge>Отозван</Badge>}
                  {manage && k.status === 'active' && <ConfirmButton variant="ghost" title="Отозвать ключ?" text="Запросы агента с этим ключом сразу получат 401." confirmLabel="Отозвать" onConfirm={() => actions.revokeKey(k.id)}>Отозвать</ConfirmButton>}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card title="Операции агента" actions={<Link className="link" to={`/operations?agent=${agent.id}`}>Открыть в журнале →</Link>}>
        {ops.length === 0 ? <Empty>Операций пока нет. Подключите агента к шлюзу по виртуальному ключу.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Время</th><th>Ресурс</th><th>Поставщик</th><th>Решение</th><th className="num">Сумма</th><th>Квитанция</th></tr></thead>
              <tbody>
                {ops.slice(0, 10).map((o) => (
                  <tr key={o.id} className="clickable" onClick={() => navigate(`/operations/${o.id}`)}>
                    <td>{fmtDateTime(o.at)}</td><td>{o.resource}</td><td>{o.vendor}</td>
                    <td><CodeBadge code={o.code} /></td>
                    <td className="num">{o.cost ? fmtRub(o.cost) : '—'}</td>
                    <td>{o.receipt ? <code className="small">{short(o.receipt.hash, 6)}</code> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {manage && (
        <Card title="Опасная зона">
          <div className="list-row">
            <span>Удалить агента<span className="muted small block">Ключи будут отозваны, история операций и квитанции сохранятся</span></span>
            <ConfirmButton variant="danger" title="Удалить агента?" text={`Агент «${agent.name}» будет удалён, все его ключи отозваны.`} confirmLabel="Удалить" onConfirm={() => { actions.deleteAgent(agent.id); navigate('/agents'); }}>Удалить</ConfirmButton>
          </div>
        </Card>
      )}

      {editing && <AgentForm open onClose={() => setEditing(false)} agent={agent} />}

      <Modal open={!!newKey} onClose={() => setNewKey(null)} title="Новый виртуальный ключ" footer={<Button variant="primary" onClick={() => setNewKey(null)}>Готово</Button>}>
        <p>Скопируйте ключ и передайте его агенту. После закрытия окна он больше не будет показан.</p>
        <div className="key-box"><code>{newKey}</code></div>
        <Button icon="check" onClick={() => { navigator.clipboard?.writeText(newKey ?? '').then(() => setCopied(true)).catch(() => setCopied(false)); }}>{copied ? 'Скопировано' : 'Скопировать'}</Button>
        <p className="muted small">Пример подключения через SDK: <code>{`costguard.authorize(key="${newKey?.slice(0, 14)}…")`}</code></p>
      </Modal>
    </>
  );
}
