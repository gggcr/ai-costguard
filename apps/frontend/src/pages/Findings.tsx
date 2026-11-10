import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { FINDING_ACTIONS, FINDING_TRANSITIONS, useLookup, useStore, type FindingAction } from '../api/store';
import { useAuth } from '../auth/auth';
import type { FindingStatus } from '../api/types';
import { Button, Card, Empty, Field, FINDING_META, FindingBadge, Modal, PageHeader, ReadOnlyNote, Stat } from '../components/ui';
import { downloadText, fmtDate, fmtDateTime, fmtRub, toCsv } from '../lib';

const RULE_LABEL: Record<string, string> = {
  unused_licenses: 'Неиспользуемые лицензии', offboarded: 'Лицензии уволенных', duplicates: 'Дубли подписок', overlap: 'Пересекающиеся инструменты',
  oversized_plan: 'Избыточный тариф', auto_renewal: 'Скорое автопродление', invoice_errors: 'Ошибки в счетах', shadow_saas: 'Теневой SaaS',
  ai_bypass: 'ИИ-расходы мимо шлюза', annual_plan: 'Помесячно вместо годового', agent_loop: 'Аномалия ИИ-агента',
  // правила движка аудита в ядре (ADR 0005, 0032)
  'license.unused': 'Неиспользуемые лицензии', 'license.dismissed': 'Лицензии уволенных', 'subscription.idle-seats': 'Свободные места',
  'subscription.duplicate': 'Дубли подписок', 'contract.renewal': 'Скорое автопродление', 'subscription.overlap': 'Пересекающиеся инструменты',
  'subscription.oversized-plan': 'Избыточный тариф', 'subscription.monthly-billing': 'Помесячно вместо годового',
  'invoice.duplicate': 'Ошибки в счетах: дубль', 'invoice.overcharge': 'Ошибки в счетах: сумма больше подписок',
  'vendor.shadow': 'Теневые подписки', 'ai.bypass': 'ИИ-расходы мимо шлюза', 'anomaly.spike': 'Всплеск трат',
  'asset.not-returned': 'Уволенный не сдал технику', 'asset.overdue': 'Срок возврата техники прошёл', 'asset.idle': 'Техника простаивает', 'asset.duplicate': 'Лишняя техника у сотрудника',
};
// Экономия по технике разовая (стоимость устройства), а не ежемесячная, и в счетах её не видно; дубль счёта — тоже разово
const oneOff = (rule: string) => rule.startsWith('asset.') || rule === 'invoice.duplicate';
const savingsText = (rule: string, v: number) => `${fmtRub(v)}${oneOff(rule) ? ' разово' : '/мес'}`;
const FILTERS: { id: 'open' | FindingStatus | 'all'; label: string }[] = [
  { id: 'open', label: 'Открытые' }, { id: 'new', label: 'Новые' }, { id: 'review', label: 'На проверке' }, { id: 'in_progress', label: 'Выполняются' },
  { id: 'confirmed', label: 'Подтверждены' }, { id: 'all', label: 'Все' },
];
const OPEN: FindingStatus[] = ['new', 'review', 'accepted', 'in_progress', 'postponed'];

const ENGINE_ONLY: FindingAction[] = ['autoClose', 'reopen', 'dateReached'];

export function Findings() {
  const { state } = useStore();
  const { canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const filter = (params.get('status') ?? 'open') as (typeof FILTERS)[number]['id'];
  const list = state.findings.filter((f) => filter === 'all' || (filter === 'open' ? OPEN.includes(f.status) : f.status === filter));
  const potential = state.findings.filter((f) => OPEN.includes(f.status) && !oneOff(f.rule)).reduce((s, f) => s + f.expectedSavings, 0);
  const equipment = state.findings.filter((f) => OPEN.includes(f.status) && oneOff(f.rule)).reduce((s, f) => s + f.expectedSavings, 0);
  const confirmed = state.findings.filter((f) => f.status === 'confirmed').reduce((s, f) => s + f.expectedSavings, 0);

  const exportCsv = () => downloadText('findings.csv', toCsv([
    ['ID', 'Правило', 'Находка', 'Статус', 'Владелец', 'Экономия, ₽', 'Период экономии', 'Создана'],
    ...list.map((f) => [f.id, RULE_LABEL[f.rule] ?? f.rule, f.title, FINDING_META[f.status].label, L.user(f.ownerId)?.name ?? '', f.expectedSavings, oneOff(f.rule) ? 'разово' : 'в месяц', f.createdAt.slice(0, 10)]),
  ]), 'text/csv');

  return (
    <>
      <PageHeader title="Находки" subtitle="Движок аудита ищет переплаты после каждой загрузки и каждую ночь. Экономия засчитывается, только когда видна в следующих счетах"
        actions={<Button icon="download" onClick={exportCsv}>Выгрузить CSV</Button>} />
      {!canEdit('findings') && <ReadOnlyNote />}
      <div className="stats">
        <Stat label="Потенциальная экономия" value={`${fmtRub(potential)}/мес`} hint={`${fmtRub(potential * 12)} в год${equipment ? ` · техника ${fmtRub(equipment)} разово` : ''}`} />
        <Stat label="Подтверждено" value={`${fmtRub(confirmed)}/мес`} hint="видно в счетах" />
        <Stat label="Новых находок" value={state.findings.filter((f) => f.status === 'new').length} hint="ждут владельца" />
      </div>
      <div className="chips" role="tablist">
        {FILTERS.map((f) => <button key={f.id} role="tab" aria-selected={filter === f.id} className={`chip ${filter === f.id ? 'active' : ''}`} onClick={() => setParams({ status: f.id })}>{f.label}</button>)}
      </div>
      <Card>
        {list.length === 0 ? <Empty>Находок с таким статусом нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Находка</th><th>Правило</th><th>Владелец</th><th>Статус</th><th className="num">Экономия</th></tr></thead>
              <tbody>
                {list.map((f) => (
                  <tr key={f.id} className="clickable" onClick={() => navigate(`/findings/${f.id}`)}>
                    <td><Link to={`/findings/${f.id}`} onClick={(e) => e.stopPropagation()}>{f.title}</Link><span className="muted small block">{fmtDate(f.createdAt)}</span></td>
                    <td>{RULE_LABEL[f.rule] ?? f.rule}</td>
                    <td>{L.user(f.ownerId)?.name ?? <span className="muted">не назначен</span>}</td>
                    <td><FindingBadge status={f.status} /></td>
                    <td className="num"><strong>{savingsText(f.rule, f.expectedSavings)}</strong></td>
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

// Схема жизненного цикла с подсветкой текущего статуса
function Lifecycle({ status }: { status: FindingStatus }) {
  const main: FindingStatus[] = ['new', 'review', 'accepted', 'in_progress', 'confirmed'];
  const side: FindingStatus[] = ['postponed', 'rejected', 'auto_closed'];
  const idx = main.indexOf(status);
  return (
    <div className="lifecycle">
      <ol className="pipeline">
        {main.map((s, i) => (
          <li key={s} className={s === status ? 'current' : idx > i ? 'done' : ''}>
            <span className="pipeline-dot">{idx > i ? '✓' : i + 1}</span>
            <span className="small">{FINDING_META[s].label}</span>
          </li>
        ))}
      </ol>
      <div className="lifecycle-side">
        {side.map((s) => <span key={s} className={`side-state ${s === status ? 'current' : ''}`}>{FINDING_META[s].label}</span>)}
      </div>
    </div>
  );
}

export function FindingDetail() {
  const { id } = useParams();
  const { state, actions, mode } = useStore();
  const { user, canEdit } = useAuth();
  const L = useLookup();
  const [modal, setModal] = useState<FindingAction | null>(null);
  const [ownerId, setOwnerId] = useState('');
  const [date, setDate] = useState(new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10));
  const [reason, setReason] = useState('');
  const f = state.findings.find((x) => x.id === id);
  if (!f) return <Empty action={<Link to="/findings" className="btn btn-primary">К находкам</Link>}>Находка не найдена</Empty>;

  const edit = canEdit('findings') && (user?.role !== 'agent_owner' || f.ownerId === user.id);
  const sub = L.subscription(f.subscriptionId);
  const agent = L.agent(f.agentId);
  // Технику в счетах не видно: её находки закрывает возврат, а не «видна в счёте» (ADR 0020)
  // autoClose, reopen и dateReached в ядре делает движок аудита и таймер, человеку они не доступны
  const available = FINDING_TRANSITIONS[f.status].filter((a) => !(f.rule.startsWith('asset.') && (a === 'confirm' || a === 'missInvoice'))
    && !(mode === 'api' && ENGINE_ONLY.includes(a)));
  const needsInput: FindingAction[] = ['assign', 'postpone', 'reject', 'createTask'];

  const run = (a: FindingAction) => {
    if (needsInput.includes(a)) {
      setModal(a);
      setReason('');
      setOwnerId(f.ownerId ?? state.users.find((u) => u.role === 'it_admin')?.id ?? '');
      return;
    }
    actions.findingAction(f.id, a, user?.name ?? '');
  };
  const submit = () => {
    if (!modal) return;
    actions.findingAction(f.id, modal, user?.name ?? '', { ownerId, date: new Date(date).toISOString(), reason });
    setModal(null);
  };
  const invalid = (modal === 'assign' && !ownerId) || (modal === 'reject' && !reason.trim());
  const primary: FindingAction[] = ['accept', 'assign', 'createTask', 'confirm', 'reopen', 'dateReached'];

  return (
    <>
      <PageHeader back={{ to: '/findings', label: 'Находки' }} title={f.title} subtitle={`${RULE_LABEL[f.rule] ?? f.rule} · создана ${fmtDate(f.createdAt)}`} actions={<FindingBadge status={f.status} />} />
      <Card><Lifecycle status={f.status} /></Card>
      <div className="grid-2">
        <Card title="Суть">
          <p>{f.description}</p>
          <dl className="dl">
            <dt>Экономия</dt><dd>{oneOff(f.rule)
              ? <><strong>{fmtRub(f.expectedSavings)}</strong> разово: стоимость техники, в счетах не видна</>
              : <><strong>{fmtRub(f.expectedSavings)}</strong> в месяц · {fmtRub(f.expectedSavings * 12)} в год</>}</dd>
            {f.assetId && <><dt>Техника</dt><dd><Link to={`/assets/${f.assetId}`}>{state.assets.find((a) => a.id === f.assetId)?.model ?? 'карточка'}</Link></dd></>}
            <dt>Владелец</dt><dd>{L.user(f.ownerId)?.name ?? 'не назначен'}</dd>
            {sub && <><dt>Подписка</dt><dd><Link to={`/software/subscriptions/${sub.id}`}>{sub.product}</Link></dd></>}
            {agent && <><dt>Агент</dt><dd><Link to={`/agents/${agent.id}`}>{agent.name}</Link></dd></>}
            {f.postponedUntil && <><dt>Отложена до</dt><dd>{fmtDate(f.postponedUntil)}</dd></>}
            {f.rejectReason && <><dt>Причина отказа</dt><dd>{f.rejectReason}</dd></>}
            {f.status === 'in_progress' && <><dt>Счетов без экономии</dt><dd>{f.missedInvoices} из 2</dd></>}
          </dl>
        </Card>
        <Card title="Действия">
          {!edit ? <p className="muted">Менять статус может владелец находки, финансист, ИТ-администратор или владелец компании.</p>
            : available.length === 0 ? <p className="muted">Находка закрыта, действий нет.</p> : (
              <div className="stack-sm">
                {available.map((a) => (
                  <Button key={a} variant={primary.includes(a) ? 'primary' : a === 'reject' ? 'danger' : 'secondary'} onClick={() => run(a)}>{FINDING_ACTIONS[a]}</Button>
                ))}
                {(available.includes('confirm') || available.includes('autoClose') || available.includes('dateReached') || available.includes('reopen')) && (
                  <p className="muted small">В рабочей версии «экономия видна в счёте», «условие исчезло» и «наступила дата» срабатывают автоматически по данным импорта. В прототипе их можно нажать вручную.</p>
                )}
              </div>
            )}
        </Card>
      </div>
      <Card title="История">
        <ol className="timeline">
          {[...f.history].reverse().map((h, i) => (
            <li key={i}><span className="muted small">{fmtDateTime(h.at)} · {h.by}</span><span>{h.text}</span></li>
          ))}
        </ol>
      </Card>

      <Modal open={!!modal} onClose={() => setModal(null)} title={modal ? FINDING_ACTIONS[modal] : ''} footer={
        <><Button variant="ghost" onClick={() => setModal(null)}>Отмена</Button><Button variant={modal === 'reject' ? 'danger' : 'primary'} disabled={invalid} onClick={submit}>{modal ? FINDING_ACTIONS[modal] : ''}</Button></>
      }>
        <div className="form">
          {modal === 'assign' && (
            <Field label="Владелец находки">
              <select className="input" value={ownerId} onChange={(e) => setOwnerId(e.target.value)}>
                <option value="">Выберите</option>
                {state.users.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            </Field>
          )}
          {modal === 'postpone' && <Field label="Отложить до"><input className="input" type="date" value={date} min={new Date().toISOString().slice(0, 10)} onChange={(e) => setDate(e.target.value)} /></Field>}
          {modal === 'reject' && <Field label="Причина отказа" hint="Обязательно: движок аудита учтёт её и не будет повторять находку"><textarea className="input" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>}
          {modal === 'createTask' && <Field label="Что сделать" hint="Задача уйдёт ответственному"><textarea className="input" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Например, снять 5 лицензий до 30 ноября" /></Field>}
        </div>
      </Modal>
    </>
  );
}
