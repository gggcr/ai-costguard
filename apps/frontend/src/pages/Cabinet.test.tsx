// @vitest-environment jsdom
// «Мой кабинет» (ADR 0051): сотрудник видит себя и не видит разделов компании.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { StoreProvider } from '../api/store';
import { AuthProvider, userFromClaims, useAuth, type Section } from '../auth/auth';
import { Guard } from '../components/Layout';
import { Cabinet } from './Cabinet';

function open(userId: string, path = '/cabinet') {
  localStorage.setItem('costguard-demo-user', userId);
  render(
    <MemoryRouter initialEntries={[path]}>
      <StoreProvider>
        <AuthProvider>
          <Routes>
            <Route path="/cabinet" element={<Guard section="cabinet"><Cabinet /></Guard>} />
            <Route path="/agents" element={<Guard section="agents"><p>агенты</p></Guard>} />
            <Route path="/sections" element={<Sections />} />
          </Routes>
        </AuthProvider>
      </StoreProvider>
    </MemoryRouter>,
  );
}

const ALL: Section[] = ['dashboard', 'agents', 'policies', 'budgets', 'approvals', 'operations', 'software', 'assets',
  'imports', 'connections', 'findings', 'analytics', 'org', 'notifications', 'cabinet', 'people'];

function Sections() {
  const { canView } = useAuth();
  return <p data-testid="sections">{ALL.filter(canView).join(',')}</p>;
}

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe('сотрудник', () => {
  it('видит свой профиль', () => {
    open('u-emp');
    expect(screen.getByText('Мой кабинет')).toBeTruthy();
    expect(screen.getAllByText('Ольга Новикова').length).toBeGreaterThan(0);
    expect(screen.getByText('Разработчик')).toBeTruthy();
    expect(screen.getAllByText('Сотрудник').length).toBe(2); // подпись поля и бейдж роли
  });

  it('видит только свой кабинет', () => {
    open('u-emp', '/sections');
    expect(screen.getByTestId('sections').textContent).toBe('cabinet');
  });

  it('не попадает в разделы компании', () => {
    open('u-emp', '/agents');
    expect(screen.queryByText('агенты')).toBeNull();
  });
});

describe('персонал', () => {
  it('владелец со связью с сотрудником видит и компанию, и свой кабинет', () => {
    open('u-owner', '/sections');
    const sections = screen.getByTestId('sections').textContent ?? '';
    expect(sections).toContain('agents');
    expect(sections).toContain('cabinet');
  });
});

describe('роль из токена', () => {
  it('старшая роль выигрывает, employee — младшая', () => {
    const both = userFromClaims({ sub: 's', exp: 0, email: 'a@b.c', realm_access: { roles: ['employee', 'finance'] } }, []);
    expect(both?.role).toBe('finance');
    const only = userFromClaims({ sub: 's', exp: 0, email: 'a@b.c', realm_access: { roles: ['employee', 'offline_access'] } }, []);
    expect(only?.role).toBe('employee');
  });

  it('профиль из /api/v1/me даёт имя и сотрудника', () => {
    const user = userFromClaims({ sub: 's', exp: 0, email: 'a@b.c', realm_access: { roles: ['employee'] } }, [], {
      userId: 'u1', name: 'Иванов Иван', email: 'a@b.c', roles: ['employee'],
      employee: { id: 'e1', name: 'Иванов Иван', position: 'Разработчик', department: 'ИТ', email: 'a@b.c' },
    });
    expect(user).toMatchObject({ id: 'u1', name: 'Иванов Иван', employeeId: 'e1', role: 'employee' });
  });
});
