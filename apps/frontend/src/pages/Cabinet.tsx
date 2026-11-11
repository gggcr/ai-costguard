// «Мой кабинет» (ADR 0051): всё о человеке, который вошёл, и ничего о компании. В режиме API — GET /api/v1/me,
// в демо — из демо-данных. Документы, отпуска, зарплата, лицензии и запросы появятся следующими частями.
import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import { api, ApiError } from '../api/http';
import { useStore } from '../api/store';
import type { Me } from '../api/types';
import { ROLE_LABEL, useAuth } from '../auth/auth';
import { Badge, Card, Empty, PageHeader } from '../components/ui';
import { ChannelSettings } from './ChannelSettings';
import { MyDocuments, MyNotifications } from './cabinet/Documents';
import { MyVacations } from './cabinet/Vacations';
import { MyPay } from './cabinet/Payroll';

/** Профиль того, кто вошёл: из ядра в режиме API, из демо-данных в демо. */
export function useMe(): { me: Me | null; error: string | null } {
  const { mode, state } = useStore();
  const { user } = useAuth();
  const [remote, setRemote] = useState<Me | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (mode !== 'api') return;
    let cancelled = false;
    api<Me>('GET', '/api/v1/me')
      .then((m) => { if (!cancelled) setRemote(m); })
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof ApiError ? e.message : 'Сервер CostGuard не отвечает'); });
    return () => { cancelled = true; };
  }, [mode]);
  if (mode === 'api') return { me: remote, error };
  if (!user) return { me: null, error: null };
  const e = state.employees.find((x) => x.id === user.employeeId && x.active);
  const department = e ? state.departments.find((d) => d.id === e.departmentId)?.name ?? '' : '';
  return {
    me: {
      userId: user.id, name: user.name, email: user.email, roles: [user.role],
      employee: e ? { id: e.id, name: e.name, position: e.position, department, email: e.email } : null,
    },
    error: null,
  };
}


/** Вкладки кабинета: каждая часть кабинета добавляет свою (ADR 0051–0057). */
export const CABINET_TABS: { to: string; label: string; api: boolean }[] = [
  { to: '', label: 'Профиль', api: false },
  { to: 'notifications', label: 'Уведомления', api: true },
  { to: 'documents', label: 'Документы', api: true },
  { to: 'vacations', label: 'Отпуска', api: true },
  { to: 'pay', label: 'Зарплата', api: true },
];

function ApiOnly({ children }: { children: ReactNode }) {
  const { mode } = useStore();
  return mode === 'api' ? <>{children}</> : <Empty>Раздел работает при подключении к серверу CostGuard</Empty>;
}

export function Cabinet() {
  const { me, error } = useMe();
  if (error) return <Empty>{error}</Empty>;
  if (!me) return <Empty>Загрузка…</Empty>;
  return (
    <>
      <PageHeader title="Мой кабинет" subtitle="Здесь только ваше: профиль, документы, отпуска, зарплата и запросы" />
      {me.employee && (
        <nav className="tabs cabinet-tabs" aria-label="Разделы кабинета">
          {CABINET_TABS.map((t) => (
            <NavLink key={t.to} to={t.to === '' ? '/cabinet' : `/cabinet/${t.to}`} end className={({ isActive }) => `tab ${isActive ? 'active' : ''}`}>{t.label}</NavLink>
          ))}
        </nav>
      )}
      <Routes>
        <Route index element={<Profile me={me} />} />
        {me.employee && <Route path="notifications" element={<ApiOnly><MyNotifications /></ApiOnly>} />}
        {me.employee && <Route path="documents" element={<ApiOnly><MyDocuments /></ApiOnly>} />}
        {me.employee && <Route path="vacations" element={<ApiOnly><MyVacations /></ApiOnly>} />}
        {me.employee && <Route path="pay" element={<ApiOnly><MyPay /></ApiOnly>} />}
      </Routes>
    </>
  );
}

function Profile({ me }: { me: Me }) {
  const { mode } = useStore();
  const p = me.employee;
  return (
    <>
      <Card title="Профиль">
        {p ? (
          <dl className="dl">
            <dt>Сотрудник</dt><dd>{p.name}</dd>
            <dt>Должность</dt><dd>{p.position}</dd>
            <dt>Отдел</dt><dd>{p.department}</dd>
            <dt>Почта</dt><dd>{p.email}</dd>
            <dt>Роль в CostGuard</dt>
            <dd>{me.roles.map((r) => <Badge key={r}>{ROLE_LABEL[r as keyof typeof ROLE_LABEL] ?? r}</Badge>)}</dd>
          </dl>
        ) : (
          <Empty>Вы не числитесь сотрудником компании в CostGuard. Если это ошибка, напишите администратору.</Empty>
        )}
      </Card>
      {mode === 'api' && <ChannelSettings />}
    </>
  );
}
