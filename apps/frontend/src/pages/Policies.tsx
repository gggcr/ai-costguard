import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Mandate } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Field, Modal, PageHeader, ReadOnlyNote } from '../components/ui';
import { currencySign, fmtDate, fmtMoney } from '../lib';

const ALL_MODELS = ['gpt-5-mini', 'gpt-5', 'claude-sonnet-5-5', 'claude-opus-5-5', 'GigaChat-Pro', 'YandexGPT-5'];
const ALL_VENDORS = ['OpenAI', 'Anthropic', 'Сбер GigaChat', 'Yandex Cloud', 'СПАРК-Интерфакс'];

export const LADDER = [
  { n: 1, name: 'Ключ и агент', text: 'Ключ действителен, агент не остановлен', fail: '401 / 423' },
  { n: 2, name: 'Мандат', text: 'Модель, поставщик, сумма и время разрешены', fail: '403 policy_denied' },
  { n: 3, name: 'Повторы и петли', text: 'Нет повтора той же операции в задаче', fail: '409 duplicate_operation' },
  { n: 4, name: 'Риск агента', text: 'Риск-оценка детектора ниже порога', fail: '423 / 429' },
  { n: 5, name: 'Резерв бюджета', text: 'Хватает денег на всех уровнях: агент, отдел, компания', fail: '402 budget_exceeded' },
  { n: 6, name: 'ИИ-оценщик', text: 'Для дорогих операций: соответствует ли цели задачи', fail: 'на согласование' },
  { n: 7, name: 'Согласование', text: 'Выше порога мандата решает человек', fail: 'отказ, резерв возвращается' },
];

interface SimResult { total: number; denied: number; approval: number; examples: string[] }

export function Policies() {
  const { state, actions } = useStore();
  const { canEdit } = useAuth();
  const [params, setParams] = useSearchParams();
  const [editing, setEditing] = useState<Mandate | 'new' | null>(null);
  const [sim, setSim] = useState<{ m: Mandate; r: SimResult } | null>(null);
  const edit = canEdit('policies');
  const highlight = params.get('open');

  useEffect(() => {
    if (highlight) document.getElementById(`m-${highlight}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [highlight]);

  const simulate = (m: Mandate) => {
    // Прогон операций за 14 дней через новый мандат: что было бы запрещено или ушло на согласование
    const agentIds = state.agents.filter((a) => a.mandateId === m.id).map((a) => a.id);
    const ops = state.operations.filter((o) => (agentIds.length ? agentIds.includes(o.agentId) : true) && o.code === 'allowed');
    const examples: string[] = [];
    let denied = 0;
    let approval = 0;
    ops.forEach((o) => {
      const vendorOk = m.vendors.includes(o.vendor) || o.vendor === 'По согласованию';
      const modelOk = !ALL_MODELS.includes(o.resource) || m.models.includes(o.resource);
      if (!vendorOk || !modelOk || o.cost > m.maxPerOperation) {
        denied += 1;
        if (examples.length < 4) examples.push(`${o.resource} за ${fmtMoney(o.cost)}: ${!vendorOk ? 'поставщик не разрешён' : !modelOk ? 'модель не разрешена' : 'выше лимита на операцию'}`);
      } else if (o.cost > m.approvalThreshold) approval += 1;
    });
    setSim({ m, r: { total: ops.length, denied, approval, examples } });
  };

  return (
    <>
      <PageHeader title="Мандаты и правила" subtitle="Мандат определяет, что агенту можно покупать. Шлюз применяет опубликованную версию до оплаты"
        actions={edit && <Button variant="primary" icon="plus" onClick={() => setEditing('new')}>Новый мандат</Button>} />
      {!edit && <ReadOnlyNote />}

      <div className="cards-grid">
        {state.mandates.map((m) => {
          const used = state.agents.filter((a) => a.mandateId === m.id);
          return (
            <Card key={m.id} className={highlight === m.id ? 'highlight' : ''}
              title={<span id={`m-${m.id}`}>{m.name}</span>}
              actions={m.status === 'published' ? <Badge tone="good">Опубликован · v{m.version}</Badge> : <Badge tone="warning">Черновик</Badge>}>
              <dl className="dl">
                <dt>Модели</dt><dd>{m.models.join(', ')}</dd>
                <dt>Поставщики</dt><dd>{m.vendors.join(', ')}</dd>
                <dt>Лимит на операцию</dt><dd>{fmtMoney(m.maxPerOperation)}</dd>
                <dt>Согласование</dt><dd>выше {fmtMoney(m.approvalThreshold)}</dd>
                <dt>Время работы</dt><dd>{m.hours}</dd>
                <dt>Агенты</dt><dd>{used.length ? used.map((a, i) => <span key={a.id}>{i > 0 && ', '}<Link to={`/agents/${a.id}`}>{a.name}</Link></span>) : <span className="muted">не назначен</span>}</dd>
              </dl>
              <p className="muted small">Изменён {fmtDate(m.updatedAt)}</p>
              <div className="row-gap wrap">
                <Button onClick={() => simulate(m)}>Симулировать</Button>
                {edit && <Button onClick={() => setEditing(m)}>Изменить</Button>}
                {edit && m.status === 'draft' && <Button variant="primary" onClick={() => actions.publishMandate(m.id)}>Опубликовать</Button>}
                {edit && m.status === 'draft' && used.length === 0 && (
                  <ConfirmButton variant="ghost" title="Удалить черновик?" text={`Черновик «${m.name}» будет удалён.`} confirmLabel="Удалить" onConfirm={() => actions.deleteMandate(m.id)}>Удалить</ConfirmButton>
                )}
              </div>
            </Card>
          );
        })}
      </div>

      <Card title="Лестница проверок шлюза">
        <p className="muted small">Дешёвые проверки проходят все операции, дорогие — только крупные. Риск проверяется до резерва.</p>
        <ol className="ladder">
          {LADDER.map((s) => (
            <li key={s.n}>
              <span className="ladder-n">{s.n}</span>
              <span><strong>{s.name}</strong><span className="muted small block">{s.text}</span></span>
              <span className="muted small">иначе: {s.fail}</span>
            </li>
          ))}
        </ol>
      </Card>

      {editing && <MandateForm mandate={editing === 'new' ? undefined : editing} onClose={() => { setEditing(null); if (highlight) setParams({}); }} />}

      <Modal open={!!sim} onClose={() => setSim(null)} title={`Симуляция: ${sim?.m.name ?? ''}`} footer={
        <>
          <Button variant="ghost" onClick={() => setSim(null)}>Закрыть</Button>
          {edit && sim?.m.status === 'draft' && <Button variant="primary" onClick={() => { actions.publishMandate(sim.m.id); setSim(null); }}>Опубликовать</Button>}
        </>
      }>
        {sim && (
          <>
            <p>Разрешённые операции за 14 дней прогнаны через этот мандат.</p>
            <div className="stats compact">
              <div className="stat"><div className="stat-label">Операций</div><div className="stat-value">{sim.r.total}</div></div>
              <div className="stat"><div className="stat-label">Были бы запрещены</div><div className="stat-value">{sim.r.denied}</div></div>
              <div className="stat"><div className="stat-label">Ушли бы на согласование</div><div className="stat-value">{sim.r.approval}</div></div>
            </div>
            {sim.r.examples.length > 0 && <><p className="field-label">Примеры отказов</p><ul className="bullets">{sim.r.examples.map((e) => <li key={e}>{e}</li>)}</ul></>}
          </>
        )}
      </Modal>
    </>
  );
}

function MandateForm({ mandate, onClose }: { mandate?: Mandate; onClose: () => void }) {
  const { actions } = useStore();
  const [name, setName] = useState(mandate?.name ?? '');
  const [models, setModels] = useState<string[]>(mandate?.models ?? ['gpt-5-mini']);
  const [vendors, setVendors] = useState<string[]>(mandate?.vendors ?? ['OpenAI']);
  const [maxPerOperation, setMax] = useState(mandate?.maxPerOperation ?? 500);
  const [approvalThreshold, setThreshold] = useState(mandate?.approvalThreshold ?? 300);
  const [hours, setHours] = useState(mandate?.hours ?? 'круглосуточно');
  const toggle = (arr: string[], v: string, set: (x: string[]) => void) => set(arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);
  const invalid = !name.trim() || models.length === 0 || vendors.length === 0 || approvalThreshold > maxPerOperation;

  return (
    <Modal open onClose={onClose} title={mandate ? `Изменить «${mandate.name}»` : 'Новый мандат'} wide footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button>
        <Button variant="primary" disabled={invalid} onClick={() => { actions.saveMandate({ id: mandate?.id, name, models, vendors, maxPerOperation, approvalThreshold, hours }); onClose(); }}>Сохранить черновик</Button></>
    }>
      <div className="form">
        <Field label="Название"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <div className="form-row">
          <fieldset className="checks"><legend className="field-label">Разрешённые модели</legend>
            {ALL_MODELS.map((m) => <label key={m}><input type="checkbox" checked={models.includes(m)} onChange={() => toggle(models, m, setModels)} /> {m}</label>)}
          </fieldset>
          <fieldset className="checks"><legend className="field-label">Разрешённые поставщики</legend>
            {ALL_VENDORS.map((v) => <label key={v}><input type="checkbox" checked={vendors.includes(v)} onChange={() => toggle(vendors, v, setVendors)} /> {v}</label>)}
          </fieldset>
        </div>
        <div className="form-row">
          <Field label={`Лимит на одну операцию, ${currencySign()}`}><input className="input" type="number" min={0} value={maxPerOperation} onChange={(e) => setMax(Number(e.target.value))} /></Field>
          <Field label={`Согласование человеком выше, ${currencySign()}`} hint={approvalThreshold > maxPerOperation ? 'Порог не может быть выше лимита' : undefined}>
            <input className="input" type="number" min={0} value={approvalThreshold} onChange={(e) => setThreshold(Number(e.target.value))} />
          </Field>
          <Field label="Время работы">
            <select className="input" value={hours} onChange={(e) => setHours(e.target.value)}>
              {['круглосуточно', '09:00–21:00', '08:00–20:00', 'только рабочие дни'].map((h) => <option key={h}>{h}</option>)}
            </select>
          </Field>
        </div>
        <p className="muted small">После сохранения мандат станет черновиком. Шлюз применит его только после публикации.</p>
      </div>
    </Modal>
  );
}
