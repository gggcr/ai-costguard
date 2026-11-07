import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { ROLE_HINT, ROLE_LABEL, useAuth } from '../auth/auth';
import type { Employee, Role } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Field, Modal, PageHeader, ReadOnlyNote, Tabs } from '../components/ui';

const ROLES: Role[] = ['owner', 'finance', 'it_admin', 'agent_owner', 'auditor'];

export function Org() {
  const { state, actions } = useStore();
  const { user, canEdit } = useAuth();
  const L = useLookup();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as 'people' | 'users') ?? 'people';
  const highlight = params.get('employee');
  const [dept, setDept] = useState('all');
  const [modal, setModal] = useState<'dept' | 'employee' | 'invite' | null>(null);
  const [editEmployee, setEditEmployee] = useState<Employee | undefined>();
  const edit = canEdit('org');

  const people = state.employees.filter((e) => dept === 'all' || e.departmentId === dept);

  return (
    <>
      <PageHeader title="Оргструктура и доступ" subtitle="Отделы и сотрудники нужны для бюджетов и поиска лицензий уволенных. Пользователи и роли синхронизируются с Keycloak"
        actions={edit && (tab === 'people'
          ? <><Button onClick={() => setModal('dept')}>Новый отдел</Button><Button variant="primary" icon="plus" onClick={() => { setEditEmployee(undefined); setModal('employee'); }}>Добавить сотрудника</Button></>
          : <Button variant="primary" icon="plus" onClick={() => setModal('invite')}>Пригласить пользователя</Button>)} />
      {!edit && <ReadOnlyNote />}
      <Tabs value={tab} onChange={(t) => setParams({ tab: t })} items={[{ id: 'people', label: `Сотрудники (${state.employees.length})` }, { id: 'users', label: `Пользователи CostGuard (${state.users.length})` }]} />

      {tab === 'people' && (
        <>
          <div className="chips">
            <button className={`chip ${dept === 'all' ? 'active' : ''}`} onClick={() => setDept('all')}>Все отделы</button>
            {state.departments.map((d) => <button key={d.id} className={`chip ${dept === d.id ? 'active' : ''}`} onClick={() => setDept(d.id)}>{d.name} · {state.employees.filter((e) => e.departmentId === d.id).length}</button>)}
          </div>
          <Card>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Сотрудник</th><th>Отдел</th><th>Должность</th><th className="num">Лицензий</th><th className="num">Техники</th><th>Статус</th><th /></tr></thead>
                <tbody>
                  {people.map((e) => {
                    const licenses = state.assignments.filter((a) => a.employeeId === e.id);
                    const held = state.assetAssignments.filter((a) => a.employeeId === e.id && !a.returnedAt).length;
                    return (
                      <tr key={e.id} className={highlight === e.id ? 'row-highlight' : ''}>
                        <td>{e.name}<span className="muted small block">{e.email}</span></td>
                        <td>{L.dept(e.departmentId)?.name}</td>
                        <td>{e.position}</td>
                        <td className="num" title={licenses.map((l) => L.subscription(l.subscriptionId)?.product).join(', ')}>{licenses.length}</td>
                        <td className="num">{held ? <Link to={`/assets?holder=${e.id}`}>{held}</Link> : 0}</td>
                        <td>{e.active ? <Badge tone="good">Работает</Badge> : <Badge tone="critical">Уволен</Badge>}</td>
                        <td>{edit && (
                          <span className="row-gap">
                            <Button variant="ghost" onClick={() => { setEditEmployee(e); setModal('employee'); }}>Изменить</Button>
                            {e.active
                              ? <ConfirmButton variant="ghost" title="Отметить увольнение?" text={`${e.name}: движок аудита проверит лицензии (${licenses.length}) и технику (${held}) и создаст находки.`} confirmLabel="Отметить" onConfirm={() => actions.setEmployeeActive(e.id, false)}>Уволен</ConfirmButton>
                              : <Button variant="ghost" onClick={() => actions.setEmployeeActive(e.id, true)}>Восстановить</Button>}
                          </span>
                        )}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      {tab === 'users' && (
        <Card>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Пользователь</th><th>Роль</th><th>Что может</th><th /></tr></thead>
              <tbody>
                {state.users.map((u) => (
                  <tr key={u.id}>
                    <td>{u.name}<span className="muted small block">{u.email}</span></td>
                    <td>
                      {edit && u.id !== user?.id ? (
                        <select className="input" value={u.role} onChange={(e) => actions.setUserRole(u.id, e.target.value as Role)} aria-label={`Роль ${u.name}`}>
                          {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                        </select>
                      ) : <>{ROLE_LABEL[u.role]}{u.id === user?.id && <span className="muted small"> (вы)</span>}</>}
                    </td>
                    <td className="muted small">{ROLE_HINT[u.role]}</td>
                    <td>{edit && u.id !== user?.id && <ConfirmButton variant="ghost" title="Закрыть доступ?" text={`${u.name} больше не сможет войти в CostGuard.`} confirmLabel="Закрыть доступ" onConfirm={() => actions.removeUser(u.id)}>Закрыть доступ</ConfirmButton>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">Агенты владельца агента настраиваются в карточке агента. <Link to="/agents">К агентам →</Link></p>
        </Card>
      )}

      {modal === 'dept' && <DeptForm onClose={() => setModal(null)} />}
      {modal === 'employee' && <EmployeeForm employee={editEmployee} onClose={() => setModal(null)} />}
      {modal === 'invite' && <InviteForm onClose={() => setModal(null)} />}
    </>
  );
}

function DeptForm({ onClose }: { onClose: () => void }) {
  const { actions } = useStore();
  const [name, setName] = useState('');
  return (
    <Modal open onClose={onClose} title="Новый отдел" footer={<><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!name.trim()} onClick={() => { actions.createDepartment(name.trim()); onClose(); }}>Создать</Button></>}>
      <Field label="Название"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
    </Modal>
  );
}

function EmployeeForm({ employee, onClose }: { employee?: Employee; onClose: () => void }) {
  const { state, actions } = useStore();
  const [name, setName] = useState(employee?.name ?? '');
  const [email, setEmail] = useState(employee?.email ?? '');
  const [position, setPosition] = useState(employee?.position ?? '');
  const [departmentId, setDepartmentId] = useState(employee?.departmentId ?? state.departments[0]?.id ?? '');
  const emailBad = email !== '' && !/^\S+@\S+\.\S+$/.test(email);
  return (
    <Modal open onClose={onClose} title={employee ? 'Изменить сотрудника' : 'Новый сотрудник'} footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!name.trim() || !email || emailBad} onClick={() => { actions.saveEmployee({ id: employee?.id, name, email, position, departmentId }); onClose(); }}>Сохранить</Button></>
    }>
      <div className="form">
        <Field label="ФИО"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Email" hint={emailBad ? 'Проверьте адрес' : undefined}><input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
        <div className="form-row">
          <Field label="Должность"><input className="input" value={position} onChange={(e) => setPosition(e.target.value)} /></Field>
          <Field label="Отдел"><select className="input" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>{state.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
        </div>
      </div>
    </Modal>
  );
}

function InviteForm({ onClose }: { onClose: () => void }) {
  const { state, actions } = useStore();
  const [employeeId, setEmployeeId] = useState('');
  const [role, setRole] = useState<Role>('agent_owner');
  const candidates = state.employees.filter((e) => e.active && !state.users.some((u) => u.employeeId === e.id));
  const emp = state.employees.find((e) => e.id === employeeId);
  return (
    <Modal open onClose={onClose} title="Пригласить пользователя" footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={!emp} onClick={() => { if (emp) actions.inviteUser({ name: emp.name, email: emp.email, role, employeeId: emp.id }); onClose(); }}>Пригласить</Button></>
    }>
      <div className="form">
        <Field label="Сотрудник"><select className="input" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}><option value="">Выберите</option>{candidates.map((e) => <option key={e.id} value={e.id}>{e.name} · {e.email}</option>)}</select></Field>
        <Field label="Роль" hint={ROLE_HINT[role]}><select className="input" value={role} onChange={(e) => setRole(e.target.value as Role)}>{ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}</select></Field>
      </div>
    </Modal>
  );
}
