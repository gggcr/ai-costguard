import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Budget } from '../api/types';
import { Badge, Button, Card, Field, Modal, PageHeader, Progress, ReadOnlyNote } from '../components/ui';
import { fmtPct, fmtRub } from '../lib';

function BudgetRow({ b, depth, onEdit, edit }: { b: Budget; depth: number; onEdit: (b: Budget) => void; edit: boolean }) {
  const pct = b.limit ? ((b.spent + b.reserved) / b.limit) * 100 : 0;
  const name = b.level === 'agent' ? <Link to={`/agents/${b.refId}`}>{b.name}</Link> : b.name;
  return (
    <tr className={`depth-${depth}`}>
      <td style={{ paddingLeft: 12 + depth * 24 }}>
        {name}
        <span className="muted small block">{b.level === 'company' ? 'Компания' : b.level === 'department' ? 'Отдел' : 'Агент'}</span>
      </td>
      <td className="num">{fmtRub(b.spent)}</td>
      <td className="num muted">{fmtRub(b.reserved)}</td>
      <td className="num">{fmtRub(b.limit)}</td>
      <td style={{ minWidth: 180 }}>
        <div className="row-gap"><Progress value={b.spent + b.reserved} max={b.limit} label={`${b.name}: ${fmtPct(pct)}`} /><span className="small">{fmtPct(pct)}</span></div>
      </td>
      <td>{pct >= 95 ? <Badge tone="critical">Почти исчерпан</Badge> : pct >= 80 ? <Badge tone="warning">Больше 80%</Badge> : <Badge tone="good">В норме</Badge>}</td>
      <td>{edit && <Button variant="ghost" onClick={() => onEdit(b)}>Изменить лимит</Button>}</td>
    </tr>
  );
}

export function Budgets() {
  const { state, actions } = useStore();
  const { canEdit } = useAuth();
  const [editing, setEditing] = useState<Budget | null>(null);
  const [limit, setLimit] = useState(0);
  const edit = canEdit('budgets');

  const company = state.budgets.filter((b) => b.level === 'company');
  const children = (id: string) => state.budgets.filter((b) => b.parentId === id);
  const rows: { b: Budget; depth: number }[] = [];
  const walk = (b: Budget, depth: number) => { rows.push({ b, depth }); children(b.id).forEach((c) => walk(c, depth + 1)); };
  company.forEach((c) => walk(c, 0));

  const open = (b: Budget) => { setEditing(b); setLimit(b.limit); };
  const childSum = editing ? children(editing.id).reduce((s, c) => s + c.limit, 0) : 0;

  return (
    <>
      <PageHeader title="Бюджеты" subtitle="Шлюз резервирует деньги на всех уровнях до вызова провайдера и списывает факт после ответа. Ноябрь 2026" />
      {!edit && <ReadOnlyNote />}
      <Card>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Уровень</th><th className="num">Списано</th><th className="num">В резерве</th><th className="num">Лимит</th><th>Заполнение</th><th>Статус</th><th /></tr></thead>
            <tbody>{rows.map(({ b, depth }) => <BudgetRow key={b.id} b={b} depth={depth} onEdit={open} edit={edit} />)}</tbody>
          </table>
        </div>
      </Card>
      <Modal open={!!editing} onClose={() => setEditing(null)} title={`Лимит: ${editing?.name ?? ''}`} footer={
        <><Button variant="ghost" onClick={() => setEditing(null)}>Отмена</Button>
          <Button variant="primary" disabled={limit <= 0} onClick={() => { if (editing) actions.setBudgetLimit(editing.id, limit); setEditing(null); }}>Сохранить</Button></>
      }>
        {editing && (
          <div className="form">
            <Field label="Лимит на месяц, ₽" hint={`Уже списано ${fmtRub(editing.spent)}, в резерве ${fmtRub(editing.reserved)}`}>
              <input className="input" type="number" min={0} step={1000} value={limit} onChange={(e) => setLimit(Number(e.target.value))} />
            </Field>
            {limit < editing.spent + editing.reserved && <div className="alert alert-warning">Лимит меньше уже потраченного: новые операции получат 402 budget_exceeded.</div>}
            {childSum > limit && <div className="alert alert-warning">Сумма лимитов вложенных уровней ({fmtRub(childSum)}) больше нового лимита.</div>}
          </div>
        )}
      </Modal>
    </>
  );
}
