// Техника сотрудников (кадр 15 доски): кто что держит, что пора вернуть, акты выдачи.
// В ядре это модуль assets: GET/POST /api/v1/assets, PUT/DELETE /api/v1/assets/{id}/assignment,
// GET /api/v1/assets/{id}/assignments/{assignmentId}/act (PDF акта).
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { COMPANY_NAME } from '../api/mock-data';
import type { Asset, AssetAssignment, AssetSource, AssetStatus, AssetType, Employee } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Empty, Field, FindingBadge, Modal, PageHeader, ReadOnlyNote, Stat, Tabs, type Tone } from '../components/ui';
import { downloadText, fmtDate, fmtRub, localDay, toCsv } from '../lib';

export const ASSET_TYPE_LABEL: Record<AssetType, string> = { LAPTOP: 'Ноутбук', PHONE: 'Телефон', MONITOR: 'Монитор', OTHER: 'Прочее' };
const STATUS_META: Record<AssetStatus, { label: string; tone: Tone }> = {
  ASSIGNED: { label: 'Выдана', tone: 'good' },
  IN_STOCK: { label: 'На складе', tone: 'info' },
  RETIRED: { label: 'Списана', tone: 'neutral' },
};
const SOURCE_LABEL: Record<AssetSource, string> = { manual: 'вручную', jamf: 'Jamf', intune: 'Intune', onec: '1С' };
const IDLE_DAYS = 90; // как costguard.audit.idle-days в ядре

const today = () => localDay();
const inDays = (n: number) => localDay(new Date(Date.now() + n * 86400000));
const isOverdue = (g?: AssetAssignment) => !!g?.returnDue && g.returnDue < today();
const daysSince = (isoDay: string) => Math.floor((Date.now() - new Date(isoDay).getTime()) / 86400000);

function StatusBadge({ status }: { status: AssetStatus }) {
  const m = STATUS_META[status];
  return <Badge tone={m.tone}>{m.label}</Badge>;
}

function HolderCell({ employee }: { employee?: Employee }) {
  if (!employee) return <span className="muted">—</span>;
  return <>{employee.name}{!employee.active && <> <Badge tone="critical">уволен</Badge></>}</>;
}

function ReturnDue({ g }: { g?: AssetAssignment }) {
  if (!g) return <span className="muted">—</span>;
  if (!g.returnDue) return <span className="muted">бессрочно</span>;
  return <>{fmtDate(g.returnDue)}{isOverdue(g) && <> <Badge tone="critical">срок прошёл</Badge></>}</>;
}

type Tab = 'all' | 'return';
type StatusFilter = 'all' | AssetStatus;

export function Assets() {
  const { state } = useStore();
  const showAct = useShowAct();
  const { canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) ?? 'all';
  const holder = params.get('holder');
  const [status, setStatus] = useState<StatusFilter>('all');
  const [type, setType] = useState<'all' | AssetType>('all');
  const [q, setQ] = useState('');
  const [creating, setCreating] = useState(false);
  const edit = canEdit('assets');

  const rows = useMemo(() => state.assets.map((a) => {
    const g = L.holding(a.id);
    return { a, g, e: L.employee(g?.employeeId) };
  }), [state.assets, L]);

  const held = rows.filter((r) => r.a.status === 'ASSIGNED');
  const toReturn = held.filter((r) => (r.e && !r.e.active) || isOverdue(r.g));
  const heldCost = held.reduce((s, r) => s + r.a.cost, 0);
  const lastReturn = (assetId: string) => state.assetAssignments.filter((x) => x.assetId === assetId && x.returnedAt).map((x) => x.returnedAt!).sort().pop();
  const idle = rows.filter((r) => r.a.status === 'IN_STOCK' && (() => { const d = lastReturn(r.a.id); return !!d && daysSince(d) > IDLE_DAYS; })());

  const t = q.trim().toLowerCase();
  const list = rows.filter(({ a, e }) =>
    (status === 'all' || a.status === status)
    && (type === 'all' || a.type === type)
    && (!holder || L.holding(a.id)?.employeeId === holder)
    && (!t || [a.model, a.inventoryNumber, a.serialNumber ?? '', e?.name ?? ''].some((x) => x.toLowerCase().includes(t))));

  const exportCsv = () => downloadText('equipment.csv', toCsv([
    ['Инв. №', 'Модель', 'Тип', 'Серийный №', 'Статус', 'Сотрудник', 'Выдана', 'Срок возврата', 'Акт', 'Стоимость, ₽', 'Источник'],
    ...list.map(({ a, g, e }) => [a.inventoryNumber, a.model, ASSET_TYPE_LABEL[a.type], a.serialNumber ?? '', STATUS_META[a.status].label, e?.name ?? '', g?.issuedAt ?? '', g?.returnDue ?? '', g?.actNumber ?? '', a.cost, SOURCE_LABEL[a.source]]),
  ]), 'text/csv');

  const holderName = holder ? L.employee(holder)?.name : undefined;

  return (
    <>
      <PageHeader title="Техника сотрудников" subtitle="Кто что держит, что пора вернуть и где лишние траты. Каждая выдача оформляется актом приёма-передачи в PDF. Данные приходят из Jamf, Intune и 1С или вносятся вручную"
        actions={<>
          <Button icon="download" onClick={exportCsv}>Выгрузить CSV</Button>
          {edit && <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>Добавить технику</Button>}
        </>} />
      {!edit && <ReadOnlyNote />}
      <div className="stats">
        <Stat label="На руках у сотрудников" value={held.length} hint={`на ${fmtRub(heldCost)}`} />
        <Stat label="Пора вернуть" value={toReturn.length} hint="у уволенных и с прошедшим сроком" />
        <Stat label="На складе" value={rows.filter((r) => r.a.status === 'IN_STOCK').length} hint={idle.length ? `${idle.length} без дела больше ${IDLE_DAYS} дней` : 'выдайте вместо покупки новой'} />
        <Stat label="Находки по технике" value={state.findings.filter((f) => f.rule.startsWith('asset.') && ['new', 'review', 'accepted', 'in_progress'].includes(f.status)).length} hint="открытые" to="/findings" />
      </div>
      <Tabs value={tab} onChange={(v) => setParams(v === 'all' ? {} : { tab: v })} items={[
        { id: 'all', label: `Вся техника (${state.assets.length})` },
        { id: 'return', label: `К возврату (${toReturn.length})` },
      ]} />

      {tab === 'all' && (
        <>
          <div className="row-gap wrap" style={{ marginBottom: 12 }}>
            <div className="chips" role="tablist">
              {(['all', 'ASSIGNED', 'IN_STOCK', 'RETIRED'] as StatusFilter[]).map((s) => (
                <button key={s} role="tab" aria-selected={status === s} className={`chip ${status === s ? 'active' : ''}`} onClick={() => setStatus(s)}>
                  {s === 'all' ? 'Все' : STATUS_META[s].label} · {s === 'all' ? rows.length : rows.filter((r) => r.a.status === s).length}
                </button>
              ))}
            </div>
            <select className="input" style={{ width: 'auto' }} value={type} onChange={(e) => setType(e.target.value as 'all' | AssetType)} aria-label="Тип техники">
              <option value="all">Все типы</option>
              {(Object.keys(ASSET_TYPE_LABEL) as AssetType[]).map((k) => <option key={k} value={k}>{ASSET_TYPE_LABEL[k]}</option>)}
            </select>
            <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Модель, инв. или серийный №, сотрудник" aria-label="Поиск техники" style={{ width: 320, maxWidth: '100%' }} />
            {holder && <Button variant="ghost" onClick={() => setParams({})}>Сотрудник: {holderName ?? holder} ×</Button>}
          </div>
          <Card>
            {list.length === 0 ? <Empty>Ничего не найдено</Empty> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Техника</th><th>Тип</th><th>Статус</th><th>У кого</th><th>Срок возврата</th><th>Акт</th><th className="num">Стоимость</th></tr></thead>
                  <tbody>
                    {list.map(({ a, g, e }) => (
                      <tr key={a.id} className="clickable" onClick={() => navigate(`/assets/${a.id}`)}>
                        <td><Link to={`/assets/${a.id}`} onClick={(ev) => ev.stopPropagation()}>{a.model}</Link>
                          <span className="muted small block">инв. № {a.inventoryNumber}{a.serialNumber ? ` · SN ${a.serialNumber}` : ''} · {SOURCE_LABEL[a.source]}</span></td>
                        <td>{ASSET_TYPE_LABEL[a.type]}</td>
                        <td><StatusBadge status={a.status} /></td>
                        <td><HolderCell employee={e} /></td>
                        <td><ReturnDue g={g} /></td>
                        <td>{g?.actNumber ? <button className="link-btn" onClick={(ev) => { ev.stopPropagation(); showAct(a, g); }}>{g.actNumber}</button> : <span className="muted">—</span>}</td>
                        <td className="num">{fmtRub(a.cost)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}

      {tab === 'return' && <ReturnList rows={toReturn} edit={edit} />}

      {creating && <AssetForm onClose={() => setCreating(false)} />}
    </>
  );
}

// Сценарий увольнения с доски: что числится за ушедшими и что пора вернуть по сроку
function ReturnList({ rows, edit }: { rows: { a: Asset; g?: AssetAssignment; e?: Employee }[]; edit: boolean }) {
  const { actions } = useStore();
  const byPerson = new Map<string, { e?: Employee; items: { a: Asset; g?: AssetAssignment }[] }>();
  rows.forEach((r) => {
    const key = r.g?.employeeId ?? '—';
    if (!byPerson.has(key)) byPerson.set(key, { e: r.e, items: [] });
    byPerson.get(key)!.items.push(r);
  });
  if (rows.length === 0) return <Card><Empty>Всё на месте: у уволенных ничего не числится, сроки возврата не прошли</Empty></Card>;
  return (
    <div className="stack">
      <div className="note">Сумму удержания за невозвращённое решают кадры и бухгалтерия по трудовому законодательству. Система показывает только стоимость из учётной системы.</div>
      {[...byPerson.entries()].map(([id, { e, items }]) => (
        <Card key={id} title={<>{e?.name ?? 'Сотрудник не найден'} {e && !e.active ? <Badge tone="critical">уволен</Badge> : <Badge tone="warning">срок возврата прошёл</Badge>}</>}
          actions={<strong>{fmtRub(items.reduce((s, x) => s + x.a.cost, 0))}</strong>}>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Техника</th><th>Выдана</th><th>Срок возврата</th><th>Акт</th><th className="num">Стоимость</th><th /></tr></thead>
              <tbody>
                {items.map(({ a, g }) => (
                  <tr key={a.id}>
                    <td><Link to={`/assets/${a.id}`}>{a.model}</Link><span className="muted small block">инв. № {a.inventoryNumber}</span></td>
                    <td>{fmtDate(g?.issuedAt)}</td>
                    <td><ReturnDue g={g} /></td>
                    <td>{g?.actNumber ?? <span className="muted">—</span>}</td>
                    <td className="num">{fmtRub(a.cost)}</td>
                    <td>{edit && <ConfirmButton variant="ghost" title="Принять возврат?" text={`${a.model} (инв. № ${a.inventoryNumber}) вернётся на склад, выдача ${g?.actNumber ?? ''} закроется сегодняшним днём.`} confirmLabel="Принять" onConfirm={() => actions.returnAsset(a.id)}>Принять возврат</ConfirmButton>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      ))}
    </div>
  );
}

function AssetForm({ onClose }: { onClose: () => void }) {
  const { actions } = useStore();
  const navigate = useNavigate();
  const [inventoryNumber, setInventory] = useState('');
  const [model, setModel] = useState('');
  const [type, setType] = useState<AssetType>('LAPTOP');
  const [cost, setCost] = useState(0);
  const [serialNumber, setSerial] = useState('');
  const costBad = !(cost >= 0) || Math.round(cost * 100) !== cost * 100;
  return (
    <Modal open onClose={onClose} title="Новая техника" footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button>
        <Button variant="primary" disabled={!inventoryNumber.trim() || !model.trim() || costBad} onClick={async () => {
          const id = await actions.createAsset({ inventoryNumber, model, type, cost, serialNumber: serialNumber.trim() || undefined });
          if (id) { onClose(); navigate(`/assets/${id}`); }
        }}>Поставить на учёт</Button></>
    }>
      <div className="form">
        <div className="form-row">
          <Field label="Инвентарный номер"><input className="input" value={inventoryNumber} onChange={(e) => setInventory(e.target.value)} placeholder="INV-0108" /></Field>
          <Field label="Серийный номер" hint="Необязательно. По нему технику узнают Jamf и Intune"><input className="input" value={serialNumber} onChange={(e) => setSerial(e.target.value)} /></Field>
        </div>
        <Field label="Модель"><input className="input" value={model} onChange={(e) => setModel(e.target.value)} placeholder="Lenovo ThinkPad T14 Gen 5" /></Field>
        <div className="form-row">
          <Field label="Тип"><select className="input" value={type} onChange={(e) => setType(e.target.value as AssetType)}>{(Object.keys(ASSET_TYPE_LABEL) as AssetType[]).map((k) => <option key={k} value={k}>{ASSET_TYPE_LABEL[k]}</option>)}</select></Field>
          <Field label="Стоимость, ₽" hint={costBad ? 'Не меньше нуля, не больше двух знаков после запятой' : 'Из учётной системы; CostGuard её не пересчитывает'}><input className="input" type="number" min={0} step="0.01" value={cost} onChange={(e) => setCost(Number(e.target.value))} /></Field>
        </div>
      </div>
    </Modal>
  );
}

function IssueForm({ asset, onClose }: { asset: Asset; onClose: () => void }) {
  const { state, actions, mode } = useStore();
  const { user } = useAuth();
  const [employeeId, setEmployeeId] = useState('');
  const [temporary, setTemporary] = useState(false);
  const [returnDue, setReturnDue] = useState(inDays(30));
  const candidates = state.employees.filter((e) => e.active);
  const dept = (id: string) => state.departments.find((d) => d.id === id)?.name;
  const dueBad = temporary && returnDue <= today();
  return (
    <Modal open onClose={onClose} title={`Выдать: ${asset.model}`} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button>
        <Button variant="primary" disabled={!employeeId || dueBad} onClick={async () => {
          const issued = await actions.issueAsset(asset.id, employeeId, temporary ? returnDue : null, user?.name ?? '—');
          if (!issued) return;
          onClose();
          if (mode === 'api') { if (issued.actNumber) void actions.downloadAct(asset.id, issued.id, issued.actNumber); }
          else openAct(state, asset, issued);
        }}>Выдать и сформировать акт</Button></>
    }>
      <div className="form">
        <Field label="Сотрудник" hint="Только из оргструктуры: в акте нужны ФИО, должность и отдел">
          <select className="input" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
            <option value="">Выберите сотрудника</option>
            {candidates.map((e) => <option key={e.id} value={e.id}>{e.name} · {e.position}, {dept(e.departmentId)}</option>)}
          </select>
        </Field>
        <label className="switch"><input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} /> <span>Временная выдача со сроком возврата</span></label>
        {temporary && <Field label="Вернуть до" hint={dueBad ? 'Срок должен быть позже сегодняшнего дня' : undefined}><input className="input" type="date" value={returnDue} onChange={(e) => setReturnDue(e.target.value)} /></Field>}
        <p className="muted small">После выдачи сформируется акт приёма-передачи с номером по порядку. Его распечатывают в двух экземплярах и подписывают.</p>
      </div>
    </Modal>
  );
}

function DueForm({ asset, g, onClose }: { asset: Asset; g: AssetAssignment; onClose: () => void }) {
  const { actions } = useStore();
  const [permanent, setPermanent] = useState(!g.returnDue);
  const [returnDue, setReturnDue] = useState(g.returnDue ?? inDays(30));
  return (
    <Modal open onClose={onClose} title={`Срок возврата: ${asset.model}`} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button>
        <Button variant="primary" onClick={() => { actions.moveReturnDate(asset.id, permanent ? null : returnDue); onClose(); }}>Сохранить</Button></>
    }>
      <div className="form">
        <label className="switch"><input type="checkbox" checked={permanent} onChange={(e) => setPermanent(e.target.checked)} /> <span>Бессрочно, до увольнения</span></label>
        {!permanent && <Field label="Вернуть до"><input className="input" type="date" value={returnDue} onChange={(e) => setReturnDue(e.target.value)} /></Field>}
        <p className="muted small">Акт {g.actNumber ?? ''} не меняется: он хранится в том виде, в каком был подписан.</p>
      </div>
    </Modal>
  );
}

/** Акт выдачи: в режиме API — неизменяемый PDF из ядра, в демо — страница для печати. */
function useShowAct() {
  const { state, actions, mode } = useStore();
  return (asset: Asset, g: AssetAssignment) => {
    if (mode === 'api') void actions.downloadAct(asset.id, g.id, g.actNumber ?? 'act');
    else openAct(state, asset, g);
  };
}

export function AssetDetail() {
  const { id } = useParams();
  const { state, actions } = useStore();
  const showAct = useShowAct();
  useEffect(() => { if (id) void actions.loadAssetHistory(id); }, [id, actions]);
  const { canEdit } = useAuth();
  const L = useLookup();
  const [modal, setModal] = useState<'issue' | 'due' | null>(null);
  const asset = L.asset(id);
  if (!asset) return <Empty action={<Link to="/assets" className="btn btn-primary">К технике</Link>}>Техника не найдена</Empty>;
  const edit = canEdit('assets');
  const g = L.holding(asset.id);
  const holder = L.employee(g?.employeeId);
  const history = state.assetAssignments.filter((x) => x.assetId === asset.id).sort((x, y) => y.issuedAt.localeCompare(x.issuedAt));
  const findings = state.findings.filter((f) => f.assetId === asset.id || (!!holder && f.rule.startsWith('asset.') && f.employeeId === holder.id));
  const others = holder ? state.assetAssignments.filter((x) => x.employeeId === holder.id && !x.returnedAt && x.assetId !== asset.id) : [];

  return (
    <>
      <PageHeader back={{ to: '/assets', label: 'Техника' }} title={asset.model}
        subtitle={`${ASSET_TYPE_LABEL[asset.type]} · инв. № ${asset.inventoryNumber}${asset.serialNumber ? ` · SN ${asset.serialNumber}` : ''}`}
        actions={<>
          <StatusBadge status={asset.status} />
          {edit && asset.status === 'IN_STOCK' && <Button variant="primary" onClick={() => setModal('issue')}>Выдать сотруднику</Button>}
          {edit && g && <Button onClick={() => setModal('due')}>Срок возврата</Button>}
          {edit && g && <ConfirmButton title="Принять возврат?" text={`${asset.model} вернётся на склад от сотрудника ${holder?.name ?? ''}. Выдача закроется сегодняшним днём, акт ${g.actNumber ?? ''} останется в истории.`} confirmLabel="Принять" onConfirm={() => actions.returnAsset(asset.id)}>Принять возврат</ConfirmButton>}
          {edit && asset.status === 'IN_STOCK' && <ConfirmButton variant="danger" title="Списать технику?" text="Списанную технику нельзя выдать. История выдач и акты сохранятся." confirmLabel="Списать" onConfirm={() => actions.retireAsset(asset.id)}>Списать</ConfirmButton>}
        </>} />
      <div className="stats">
        <Stat label="Стоимость" value={fmtRub(asset.cost)} hint="из учётной системы" />
        <Stat label="Источник" value={SOURCE_LABEL[asset.source]} hint={asset.source === 'manual' ? 'внесена в CostGuard' : 'синхронизируется, система источника главная'} />
        <Stat label="Выдач" value={history.length} hint={`актов: ${history.filter((x) => x.actNumber).length}`} />
      </div>
      <div className="grid-2">
        <Card title="Сейчас">
          {g ? (
            <dl className="dl">
              <dt>У кого</dt><dd><HolderCell employee={holder} />{holder && <span className="muted small block">{holder.position}, {L.dept(holder.departmentId)?.name}</span>}</dd>
              <dt>Выдана</dt><dd>{fmtDate(g.issuedAt)}{g.issuedBy.startsWith('connector:') && <span className="muted small"> · по данным {g.issuedBy.slice('connector:'.length)}</span>}</dd>
              <dt>Вернуть до</dt><dd><ReturnDue g={g} /></dd>
              <dt>Акт</dt><dd>{g.actNumber
                ? <Button icon="download" onClick={() => showAct(asset, g)}>Акт {g.actNumber}</Button>
                : <span className="muted">нет: выдача записана до появления актов</span>}</dd>
              {others.length > 0 && <><dt>Ещё у сотрудника</dt><dd>{others.map((o, i) => { const x = L.asset(o.assetId); return <span key={o.id}>{i > 0 && ', '}<Link to={`/assets/${o.assetId}`}>{x?.model}</Link></span>; })}</dd></>}
            </dl>
          ) : asset.status === 'RETIRED'
            ? <p className="muted">Списана. Выдать её нельзя, история сохранена.</p>
            : <p className="muted">На складе{(() => { const d = history.find((x) => x.returnedAt)?.returnedAt; return d ? ` с ${fmtDate(d)}` : ''; })()}. Выдайте её вместо покупки новой.</p>}
        </Card>
        <Card title="Находки">
          {findings.length === 0 ? <p className="muted">Находок нет</p> : (
            <ul className="list">{findings.map((f) => <li key={f.id}><Link to={`/findings/${f.id}`} className="list-row"><span>{f.title}<span className="block"><FindingBadge status={f.status} /></span></span><strong>{fmtRub(f.expectedSavings)}</strong></Link></li>)}</ul>
          )}
        </Card>
      </div>
      <Card title="История выдач">
        {history.length === 0 ? <Empty>Техника ещё не выдавалась</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Сотрудник</th><th>Выдана</th><th>Срок</th><th>Возвращена</th><th>Выдал</th><th>Акт</th></tr></thead>
              <tbody>
                {history.map((x) => (
                  <tr key={x.id}>
                    <td><HolderCell employee={L.employee(x.employeeId)} /></td>
                    <td>{fmtDate(x.issuedAt)}</td>
                    <td>{x.returnDue ? fmtDate(x.returnDue) : <span className="muted">бессрочно</span>}</td>
                    <td>{x.returnedAt ? fmtDate(x.returnedAt) : <Badge tone="good">на руках</Badge>}</td>
                    <td>{x.issuedBy.startsWith('connector:') ? `по данным ${x.issuedBy.slice('connector:'.length)}` : x.issuedBy}</td>
                    <td>{x.actNumber ? <button className="link-btn" onClick={() => showAct(asset, x)}>{x.actNumber}</button> : <span className="muted">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {modal === 'issue' && <IssueForm asset={asset} onClose={() => setModal(null)} />}
      {modal === 'due' && g && <DueForm asset={asset} g={g} onClose={() => setModal(null)} />}
    </>
  );
}

// ——— Акт приёма-передачи ———
// В ядре акт — неизменяемый PDF (GET .../act). В прототипе тот же текст открывается в новой вкладке;
// кнопка «Печать / сохранить в PDF» на ней вызывает печать браузера.

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
const longDate = (isoDay: string) => new Date(`${isoDay}T00:00:00`).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
const initials = (name: string) => {
  const p = name.trim().split(/\s+/);
  return p.length < 2 ? name : `${p[0]} ${p.slice(1).map((x) => `${x[0].toUpperCase()}.`).join(' ')}`;
};
const money = (v: number) => v.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).replace(/ /g, ' ');

export function actHtml(state: ReturnType<typeof useStore>['state'], asset: Asset, g: AssetAssignment): string {
  const e = state.employees.find((x) => x.id === g.employeeId);
  const dept = state.departments.find((d) => d.id === e?.departmentId)?.name ?? '';
  const name = e?.name ?? '—';
  const reported = g.issuedBy.startsWith('connector:');
  const signer = reported ? '____________________' : (g.issuedBy.includes('@') ? g.issuedBy : initials(g.issuedBy));
  const due = g.returnDue
    ? `Срок возврата: ${longDate(g.returnDue)}`
    : 'Срок возврата не установлен: оборудование возвращается по требованию Работодателя или при увольнении.';
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Акт № ${esc(g.actNumber ?? '')}</title>
<style>
  @page { size: A4; margin: 20mm; }
  body { font: 14px/1.45 "DejaVu Sans", Arial, sans-serif; color: #000; background: #fff; max-width: 180mm; margin: 24px auto; padding: 0 16px; }
  h1 { text-align: center; font-size: 19px; font-weight: 400; margin: 0; }
  .sub { text-align: center; font-size: 15px; margin: 4px 0 20px; }
  table { width: 100%; border-collapse: collapse; margin: 8px 0 14px; font-size: 12px; }
  td, th { border: 1px solid #000; padding: 4px 6px; text-align: left; vertical-align: top; font-weight: 400; }
  .sign { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 24px; margin-top: 40px; }
  .foot { margin-top: 48px; font-size: 10px; color: #666; }
  .bar { text-align: right; margin-bottom: 16px; }
  .bar button { font: inherit; padding: 6px 14px; cursor: pointer; }
  @media print { .bar { display: none; } body { margin: 0; } }
</style></head><body>
<div class="bar"><button onclick="window.print()">Печать / сохранить в PDF</button></div>
<h1>АКТ № ${esc(g.actNumber ?? '')}</h1>
<div class="sub">приёма-передачи оборудования сотруднику</div>
<p>${esc(longDate(g.issuedAt))}</p>
<p>Работодатель: ${esc(COMPANY_NAME)}<br>Сотрудник: ${esc(name)}<br>Должность: ${esc(e?.position ?? '')}<br>Подразделение: ${esc(dept)}</p>
<p>${esc(COMPANY_NAME)} (далее — Работодатель) передаёт, а сотрудник ${esc(name)} (далее — Сотрудник) принимает для выполнения трудовых обязанностей следующее оборудование:</p>
<table><tr><th>№</th><th>Наименование</th><th>Тип</th><th>Инв. №</th><th>Серийный №</th><th>Стоимость, руб.</th></tr>
<tr><td>1</td><td>${esc(asset.model)}</td><td>${ASSET_TYPE_LABEL[asset.type]}</td><td>${esc(asset.inventoryNumber)}</td><td>${esc(asset.serialNumber ?? '—')}</td><td>${money(asset.cost)}</td></tr></table>
<p>${esc(due)}</p>
<p>1. Оборудование передано в исправном состоянии. Сотрудник проверил его комплектность и работоспособность.</p>
<p>2. Сотрудник обязуется использовать оборудование для выполнения трудовых обязанностей, обеспечить его сохранность и вернуть Работодателю по окончании срока, по требованию или при увольнении.</p>
<p>3. Акт составлен в двух экземплярах, по одному для каждой стороны.</p>
<div class="sign">
  <div>Передал (от Работодателя)</div><div>Принял (Сотрудник)</div>
  <div>______________ / ${esc(signer)} /</div><div>______________ / ${esc(initials(name))} /</div>
  <div>«___» ____________ 20___ г.</div><div>«___» ____________ 20___ г.</div>
</div>
<div class="foot">Акт сформирован CostGuard автоматически при выдаче${reported ? ` по данным ${esc(g.issuedBy.slice('connector:'.length))}` : ''}. Выдача ${esc(g.id)}. Демо: в рабочей системе акт — неизменяемый PDF из ядра.</div>
</body></html>`;
}

function openAct(state: ReturnType<typeof useStore>['state'], asset: Asset, g: AssetAssignment) {
  const blob = new Blob([actHtml(state, asset, g)], { type: 'text/html;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  // Без noopener: с ним window.open всегда возвращает null, и не понять, заблокировал ли браузер окно
  const w = window.open(url, '_blank');
  if (!w) downloadText(`Акт ${g.actNumber}.html`, actHtml(state, asset, g), 'text/html');
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
