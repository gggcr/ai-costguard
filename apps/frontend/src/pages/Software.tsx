import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Contract, Subscription } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Empty, Field, FindingBadge, Modal, PageHeader, Progress, ReadOnlyNote, Stat, Tabs } from '../components/ui';
import { daysUntil, fmtDate, fmtRelative, fmtRub } from '../lib';
import { InvoicesTab } from './Invoices';

type Tab = 'subscriptions' | 'contracts' | 'vendors' | 'invoices';
const ACTIVE_DAYS = 30;

export function Software() {
  const { state } = useStore();
  const { canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) ?? 'subscriptions';
  const [modal, setModal] = useState<'sub' | 'contract' | 'vendor' | null>(null);
  const [editContract, setEditContract] = useState<Contract | undefined>();
  const edit = canEdit('software');
  const { actions } = useStore();

  const used = (s: Subscription) => state.assignments.filter((a) => a.subscriptionId === s.id && a.lastActive && Date.now() - new Date(a.lastActive).getTime() < ACTIVE_DAYS * 86400000).length;
  const monthly = state.subscriptions.reduce((s, x) => s + x.seats * x.pricePerSeat, 0);
  const idleSeats = state.subscriptions.reduce((s, x) => s + (x.seats - used(x)), 0);
  const soon = state.contracts.filter((c) => daysUntil(c.endDate) <= c.noticeDays + 14 && daysUntil(c.endDate) > 0);

  return (
    <>
      <PageHeader title="Подписки и договоры" subtitle="Всё ПО компании: сколько мест куплено, сколько реально используется, когда продление"
        actions={edit && (
          <>
            {tab === 'subscriptions' && <Button variant="primary" icon="plus" onClick={() => setModal('sub')}>Добавить подписку</Button>}
            {tab === 'contracts' && <Button variant="primary" icon="plus" onClick={() => { setEditContract(undefined); setModal('contract'); }}>Добавить договор</Button>}
            {tab === 'vendors' && <Button variant="primary" icon="plus" onClick={() => setModal('vendor')}>Добавить поставщика</Button>}
          </>
        )} />
      {!edit && <ReadOnlyNote />}
      <div className="stats">
        <Stat label="Подписки в месяц" value={fmtRub(monthly)} hint={`${state.subscriptions.length} подписок у ${new Set(state.subscriptions.map((s) => s.vendorId)).size} поставщиков`} />
        <Stat label="Неиспользуемые места" value={idleSeats} hint={`нет входа больше ${ACTIVE_DAYS} дней`} to="/findings" />
        <Stat label="Скоро продление" value={soon.length} hint="договоры, где пора уведомить поставщика" />
      </div>
      <Tabs value={tab} onChange={(t) => setParams({ tab: t })} items={[{ id: 'subscriptions', label: 'Подписки' }, { id: 'contracts', label: 'Договоры' }, { id: 'vendors', label: 'Поставщики' }, { id: 'invoices', label: 'Счета и платежи' }]} />

      {tab === 'subscriptions' && (
        <Card>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Продукт</th><th>Отдел</th><th>Используется</th><th className="num">Цена места</th><th className="num">В месяц</th><th>Продление</th></tr></thead>
              <tbody>
                {state.subscriptions.map((s) => {
                  const u = used(s);
                  return (
                    <tr key={s.id} className="clickable" onClick={() => navigate(`/software/subscriptions/${s.id}`)}>
                      <td><Link to={`/software/subscriptions/${s.id}`} onClick={(e) => e.stopPropagation()}>{s.product}</Link><span className="muted small block">{L.vendor(s.vendorId)?.name}</span></td>
                      <td>{L.dept(s.departmentId)?.name}</td>
                      <td style={{ minWidth: 150 }}>{u} из {s.seats}<Progress value={u} max={s.seats} /></td>
                      <td className="num">{fmtRub(s.pricePerSeat)}</td>
                      <td className="num">{fmtRub(s.seats * s.pricePerSeat)}</td>
                      <td>{fmtDate(s.renewalDate)}<span className="muted small block">{s.billingPeriod === 'year' ? 'годовая' : 'помесячно'}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === 'contracts' && (
        <Card>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Договор</th><th>Поставщик</th><th>Срок</th><th>Уведомить до</th><th>Автопродление</th><th className="num">Сумма</th><th /></tr></thead>
              <tbody>
                {state.contracts.map((c) => {
                  const notifyBy = new Date(new Date(c.endDate).getTime() - c.noticeDays * 86400000).toISOString();
                  const left = daysUntil(notifyBy);
                  return (
                    <tr key={c.id}>
                      <td><code>{c.number}</code></td>
                      <td>{L.vendor(c.vendorId)?.name}</td>
                      <td>{fmtDate(c.startDate)} — {fmtDate(c.endDate)}</td>
                      <td>{fmtDate(notifyBy)}<span className="block">{left < 0 ? <Badge tone="neutral">срок прошёл</Badge> : left <= 14 ? <Badge tone="critical">через {left} дн</Badge> : left <= 45 ? <Badge tone="warning">через {left} дн</Badge> : null}</span></td>
                      <td>
                        <label className="switch">
                          <input type="checkbox" checked={c.autoRenew} disabled={!edit} onChange={() => actions.toggleAutoRenew(c.id)} />
                          <span>{c.autoRenew ? 'включено' : 'выключено'}</span>
                        </label>
                      </td>
                      <td className="num">{fmtRub(c.amount)}</td>
                      <td>{edit && <Button variant="ghost" onClick={() => { setEditContract(c); setModal('contract'); }}>Изменить</Button>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === 'vendors' && (
        <Card>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Поставщик</th><th>Категория</th><th>ИНН</th><th>Подписки</th><th>Договоры</th></tr></thead>
              <tbody>
                {state.vendors.map((v) => (
                  <tr key={v.id}>
                    <td>{v.name}</td><td>{v.category}</td><td>{v.inn}</td>
                    <td>{state.subscriptions.filter((s) => s.vendorId === v.id).map((s, i) => <span key={s.id}>{i > 0 && ', '}<Link to={`/software/subscriptions/${s.id}`}>{s.product}</Link></span>)}</td>
                    <td>{state.contracts.filter((c) => c.vendorId === v.id).length || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === 'invoices' && <InvoicesTab />}

      {modal === 'sub' && <SubscriptionForm onClose={() => setModal(null)} />}
      {modal === 'contract' && <ContractForm contract={editContract} onClose={() => setModal(null)} />}
      {modal === 'vendor' && <VendorForm onClose={() => setModal(null)} />}
    </>
  );
}

function SubscriptionForm({ sub, onClose }: { sub?: Subscription; onClose: () => void }) {
  const { state, actions } = useStore();
  const navigate = useNavigate();
  const [f, setF] = useState<Omit<Subscription, 'id'>>(sub ?? { vendorId: state.vendors[0]?.id ?? '', product: '', departmentId: state.departments[0]?.id ?? '', seats: 5, pricePerSeat: 1000, billingPeriod: 'month', renewalDate: new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10) });
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF({ ...f, [k]: v });
  return (
    <Modal open onClose={onClose} title={sub ? 'Изменить подписку' : 'Новая подписка'} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button>
        <Button variant="primary" disabled={!f.product.trim()} onClick={() => { const id = actions.saveSubscription({ ...f, id: sub?.id }); onClose(); if (!sub) navigate(`/software/subscriptions/${id}`); }}>Сохранить</Button></>
    }>
      <div className="form">
        <div className="form-row">
          <Field label="Поставщик"><select className="input" value={f.vendorId} onChange={(e) => set('vendorId', e.target.value)}>{state.vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></Field>
          <Field label="Продукт"><input className="input" value={f.product} onChange={(e) => set('product', e.target.value)} /></Field>
          <Field label="Тариф"><input className="input" value={f.plan ?? ''} placeholder="например, Стандартный" onChange={(e) => set('plan', e.target.value || undefined)} /></Field>
        </div>
        <div className="form-row">
          <Field label="Отдел"><select className="input" value={f.departmentId} onChange={(e) => set('departmentId', e.target.value)}>{state.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
          <Field label="Договор"><select className="input" value={f.contractId ?? ''} onChange={(e) => set('contractId', e.target.value || undefined)}><option value="">без договора</option>{state.contracts.filter((c) => c.vendorId === f.vendorId).map((c) => <option key={c.id} value={c.id}>{c.number}</option>)}</select></Field>
        </div>
        <div className="form-row">
          <Field label="Мест"><input className="input" type="number" min={1} value={f.seats} onChange={(e) => set('seats', Number(e.target.value))} /></Field>
          <Field label="Цена места в месяц, ₽"><input className="input" type="number" min={0} value={f.pricePerSeat} onChange={(e) => set('pricePerSeat', Number(e.target.value))} /></Field>
          <Field label="Оплата"><select className="input" value={f.billingPeriod} onChange={(e) => set('billingPeriod', e.target.value as 'month' | 'year')}><option value="month">помесячно</option><option value="year">за год</option></select></Field>
          <Field label="Продление"><input className="input" type="date" value={f.renewalDate.slice(0, 10)} onChange={(e) => set('renewalDate', e.target.value)} /></Field>
        </div>
      </div>
    </Modal>
  );
}

function ContractForm({ contract, onClose }: { contract?: Contract; onClose: () => void }) {
  const { state, actions } = useStore();
  const [f, setF] = useState<Omit<Contract, 'id'>>(contract ?? { vendorId: state.vendors[0]?.id ?? '', number: '', startDate: new Date().toISOString().slice(0, 10), endDate: new Date(Date.now() + 365 * 86400000).toISOString().slice(0, 10), noticeDays: 30, autoRenew: true, amount: 0 });
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF({ ...f, [k]: v });
  return (
    <Modal open onClose={onClose} title={contract ? `Договор ${contract.number}` : 'Новый договор'} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!f.number.trim()} onClick={() => { actions.saveContract({ ...f, id: contract?.id }); onClose(); }}>Сохранить</Button></>
    }>
      <div className="form">
        <div className="form-row">
          <Field label="Поставщик"><select className="input" value={f.vendorId} onChange={(e) => set('vendorId', e.target.value)}>{state.vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></Field>
          <Field label="Номер"><input className="input" value={f.number} onChange={(e) => set('number', e.target.value)} /></Field>
        </div>
        <div className="form-row">
          <Field label="Начало"><input className="input" type="date" value={f.startDate} onChange={(e) => set('startDate', e.target.value)} /></Field>
          <Field label="Окончание"><input className="input" type="date" value={f.endDate} onChange={(e) => set('endDate', e.target.value)} /></Field>
          <Field label="Уведомить за, дней"><input className="input" type="number" min={0} value={f.noticeDays} onChange={(e) => set('noticeDays', Number(e.target.value))} /></Field>
        </div>
        <div className="form-row">
          <Field label="Сумма договора, ₽"><input className="input" type="number" min={0} value={f.amount} onChange={(e) => set('amount', Number(e.target.value))} /></Field>
          <label className="switch inline"><input type="checkbox" checked={f.autoRenew} onChange={(e) => set('autoRenew', e.target.checked)} /> <span>Автопродление</span></label>
        </div>
      </div>
    </Modal>
  );
}

function VendorForm({ onClose }: { onClose: () => void }) {
  const { actions } = useStore();
  const [name, setName] = useState('');
  const [inn, setInn] = useState('');
  const [category, setCategory] = useState('Совместная работа');
  const innBad = inn !== '' && !/^\d{10}(\d{2})?$/.test(inn);
  return (
    <Modal open onClose={onClose} title="Новый поставщик" footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!name.trim() || innBad} onClick={() => { actions.createVendor({ name, inn: inn || '—', category }); onClose(); }}>Добавить</Button></>
    }>
      <div className="form">
        <Field label="Название"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="ИНН" hint={innBad ? 'ИНН — 10 или 12 цифр' : 'Можно оставить пустым для иностранного поставщика'}><input className="input" value={inn} onChange={(e) => setInn(e.target.value.trim())} /></Field>
        <Field label="Категория"><select className="input" value={category} onChange={(e) => setCategory(e.target.value)}>{['Офисные пакеты', 'Управление проектами', 'Дизайн', 'Совместная работа', 'CRM', 'Бухгалтерия и ЭДО', 'Безопасность', 'ИИ-провайдеры', 'Облака', 'Другое'].map((c) => <option key={c}>{c}</option>)}</select></Field>
      </div>
    </Modal>
  );
}

export function SubscriptionDetail() {
  const { id } = useParams();
  const { state, actions } = useStore();
  const { canEdit } = useAuth();
  const L = useLookup();
  const [editing, setEditing] = useState(false);
  const [assignTo, setAssignTo] = useState('');
  const sub = L.subscription(id);
  if (!sub) return <Empty action={<Link to="/software" className="btn btn-primary">К подпискам</Link>}>Подписка не найдена</Empty>;
  const edit = canEdit('software');
  const assignments = state.assignments.filter((a) => a.subscriptionId === sub.id);
  const isActive = (d: string | null) => !!d && Date.now() - new Date(d).getTime() < ACTIVE_DAYS * 86400000;
  const contract = L.contract(sub.contractId);
  const findings = state.findings.filter((f) => f.subscriptionId === sub.id);
  const free = sub.seats - assignments.length;
  const candidates = state.employees.filter((e) => e.active && !assignments.some((a) => a.employeeId === e.id));

  return (
    <>
      <PageHeader back={{ to: '/software', label: 'Подписки' }} title={sub.product} subtitle={`${L.vendor(sub.vendorId)?.name} · ${L.dept(sub.departmentId)?.name}`}
        actions={edit && <Button onClick={() => setEditing(true)}>Изменить</Button>} />
      <div className="stats">
        <Stat label="Мест" value={`${assignments.length} / ${sub.seats}`} hint={`свободно ${Math.max(0, free)}`} />
        <Stat label="Активны за 30 дней" value={assignments.filter((a) => isActive(a.lastActive)).length} hint="входили в сервис" />
        <Stat label="Стоимость в месяц" value={fmtRub(sub.seats * sub.pricePerSeat)} hint={`${fmtRub(sub.pricePerSeat)} за место`} />
        <Stat label="Продление" value={fmtDate(sub.renewalDate)} hint={fmtRelative(sub.renewalDate)} />
      </div>
      <div className="grid-2">
        <Card title="Лицензии" actions={edit && free > 0 && candidates.length > 0 && (
          <div className="row-gap">
            <select className="input" value={assignTo} onChange={(e) => setAssignTo(e.target.value)} aria-label="Сотрудник">
              <option value="">Выбрать сотрудника</option>
              {candidates.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select>
            <Button disabled={!assignTo} onClick={() => { actions.assignLicense(sub.id, assignTo); setAssignTo(''); }}>Назначить</Button>
          </div>
        )}>
          {assignments.length === 0 ? <Empty>Лицензии не назначены</Empty> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Сотрудник</th><th>Последний вход</th><th /></tr></thead>
                <tbody>
                  {assignments.map((a) => {
                    const e = L.employee(a.employeeId);
                    return (
                      <tr key={a.id}>
                        <td>{e?.name}{e && !e.active && <> <Badge tone="critical">уволен</Badge></>}</td>
                        <td>{a.lastActive ? fmtRelative(a.lastActive) : 'ни разу'} {!isActive(a.lastActive) && <Badge tone="warning">не использует</Badge>}</td>
                        <td>{edit && <ConfirmButton variant="ghost" title="Снять лицензию?" text={`Лицензия ${sub.product} будет снята с сотрудника ${e?.name}.`} confirmLabel="Снять" onConfirm={() => actions.revokeLicense(a.id)}>Снять</ConfirmButton>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        <div className="stack">
          <Card title="Договор">
            {contract ? (
              <dl className="dl">
                <dt>Номер</dt><dd><code>{contract.number}</code></dd>
                <dt>Действует до</dt><dd>{fmtDate(contract.endDate)}</dd>
                <dt>Уведомить за</dt><dd>{contract.noticeDays} дней</dd>
                <dt>Автопродление</dt><dd>{contract.autoRenew ? <Badge tone="warning">включено</Badge> : <Badge>выключено</Badge>}</dd>
              </dl>
            ) : <p className="muted">Подписка без договора (оплата картой или по счёту)</p>}
            <Link to="/software?tab=contracts" className="link">Все договоры →</Link>
          </Card>
          <Card title="Находки по подписке">
            {findings.length === 0 ? <p className="muted">Находок нет</p> : (
              <ul className="list">{findings.map((f) => <li key={f.id}><Link to={`/findings/${f.id}`} className="list-row"><span>{f.title}<span className="block"><FindingBadge status={f.status} /></span></span><strong>{fmtRub(f.expectedSavings)}</strong></Link></li>)}</ul>
            )}
          </Card>
        </div>
      </div>
      {editing && <SubscriptionForm sub={sub} onClose={() => setEditing(false)} />}
    </>
  );
}
