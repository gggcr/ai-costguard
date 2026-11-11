// «Мой кабинет» (ADR 0051): всё о человеке, который вошёл, и ничего о компании. В режиме API — GET /api/v1/me,
// в демо — из демо-данных. Документы, отпуска, зарплата, лицензии и запросы появятся следующими частями.
import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/http';
import { useStore } from '../api/store';
import type { Me } from '../api/types';
import { ROLE_LABEL, useAuth } from '../auth/auth';
import { Badge, Card, Empty, PageHeader } from '../components/ui';
import { ChannelSettings } from './ChannelSettings';

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

const COMING = [
  { title: 'Мои документы', text: 'Трудовой договор, соглашения, акты выдачи техники; уведомление о каждом новом документе' },
  { title: 'Отпуска', text: 'Заявка на отпуск, согласование руководителем отдела, остаток дней' },
  { title: 'Зарплата', text: 'Суммы и даты аванса и зарплаты из 1С:ЗУП' },
  { title: 'Лицензии', text: 'Мои программы и запрос лицензии из общего пула' },
  { title: 'Покупка ПО', text: 'Запрос на покупку: система сверит с лицензиями компании' },
  { title: 'Свои траты на софт', text: 'Сообщить о покупке с личной карты; руководство видит только сводку без имён' },
];

export function Cabinet() {
  const { mode } = useStore();
  const { me, error } = useMe();
  if (error) return <Empty>{error}</Empty>;
  if (!me) return <Empty>Загрузка…</Empty>;
  const p = me.employee;
  return (
    <>
      <PageHeader title="Мой кабинет" subtitle="Здесь только ваше: профиль, документы, отпуска, зарплата и запросы" />
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
      {p && (
        <Card title="Скоро в кабинете">
          <ul className="list">
            {COMING.map((c) => (
              <li key={c.title} className="list-row"><span className="grow"><b>{c.title}</b><span className="muted small block">{c.text}</span></span></li>
            ))}
          </ul>
        </Card>
      )}
      {mode === 'api' && <ChannelSettings />}
    </>
  );
}
