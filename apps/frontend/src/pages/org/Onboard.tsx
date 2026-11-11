// Добавить сотрудника (ADR 0059): почта в Microsoft 365 компании, учётная запись CostGuard и пароль — одним шагом.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Button, Field, Modal } from '../../components/ui';
import { errorText, useApi } from '../cabinet/useApi';

interface Onboarded { email: string; password: string; mailboxCreated: boolean; employee: { name: string } }

export function OnboardForm({ onClose }: { onClose: () => void }) {
  const { state, toast, reloadOrg } = useStore();
  const login = useApi<{ connected: boolean; createMailboxes: boolean; domains: string[] }>('/api/v1/company/login');
  const mailboxes = login.data?.connected === true && login.data.createMailboxes;
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [position, setPosition] = useState('');
  const [departmentId, setDepartmentId] = useState(state.departments[0]?.id ?? '');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<Onboarded | null>(null);
  const emailBad = !mailboxes && !/^\S+@\S+\.\S+$/.test(email);

  const save = async () => {
    setBusy(true);
    try {
      const r = await api<Onboarded>('POST', '/api/v1/employees/onboarding', {
        firstName: firstName.trim(), lastName: lastName.trim(), position: position.trim(), departmentId,
        email: mailboxes ? null : email.trim(), createMailbox: mailboxes,
      });
      setDone(r);
      await reloadOrg?.();
    } catch (e) {
      toast(errorText(e), 'critical');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <Modal open onClose={onClose} title="Сотрудник добавлен" footer={<Button variant="primary" onClick={onClose}>Готово</Button>}>
        <div className="form">
          <p>{done.mailboxCreated ? 'Создана рабочая почта в Microsoft 365 и учётная запись CostGuard.' : 'Создана учётная запись CostGuard.'} Передайте сотруднику (например, вместе с ноутбуком) — пароль показывается только сейчас:</p>
          <Field label="Почта и логин"><input className="input" readOnly value={done.email} onFocus={(e) => e.target.select()} /></Field>
          <Field label="Пароль"><input className="input" readOnly value={done.password} onFocus={(e) => e.target.select()} /></Field>
          <Button onClick={() => { void navigator.clipboard?.writeText(`${done.email}\n${done.password}`); toast('Скопировано', 'good'); }}>Скопировать</Button>
          <p className="muted small">Вход: почта, затем пароль{done.mailboxCreated ? ' (тот же, что от Outlook)' : ''}. В личном кабинете сотрудник может подключить Google Authenticator и входить кодом вместо пароля.</p>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={onClose} title="Новый сотрудник" footer={
      <><Button variant="ghost" onClick={onClose}>Отмена</Button><Button variant="primary" disabled={busy || !firstName.trim() || !lastName.trim() || !position.trim() || !departmentId || emailBad} onClick={() => { void save(); }}>{busy ? 'Создаём…' : mailboxes ? 'Добавить и создать почту' : 'Добавить'}</Button></>
    }>
      <div className="form">
        <div className="form-row">
          <Field label="Имя"><input className="input" value={firstName} onChange={(e) => setFirstName(e.target.value)} /></Field>
          <Field label="Фамилия"><input className="input" value={lastName} onChange={(e) => setLastName(e.target.value)} /></Field>
        </div>
        {mailboxes
          ? <p className="muted small">Почта будет создана в Microsoft 365: имя.фамилия@{login.data?.domains[0]}. Пароль придумается сам.</p>
          : <Field label="Рабочая почта" hint={email && emailBad ? 'Проверьте адрес' : 'Microsoft 365 не подключён для создания почты — введите адрес'}><input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>}
        <div className="form-row">
          <Field label="Должность"><input className="input" value={position} onChange={(e) => setPosition(e.target.value)} /></Field>
          <Field label="Отдел"><select className="input" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>{state.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
        </div>
      </div>
    </Modal>
  );
}
