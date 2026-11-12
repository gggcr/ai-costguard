// @vitest-environment jsdom
// Зарплата для owner и finance (ADR 0054, 0070): выплаты, которые никто не получил, видны с причиной.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { StoreProvider } from '../../api/store';
import { EmployerPayroll } from './Payroll';

const TENANT = '0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a';

function jwt(claims: object): string {
  const b = (o: object) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b({ alg: 'none' })}.${b(claims)}.sig`;
}

function signIn() {
  const access = jwt({ sub: 'kc-fin', email: 'fin@romashka.example', name: 'Бухгалтер', tenant_id: TENANT, exp: Date.now() / 1000 + 3600, realm_access: { roles: ['finance'] } });
  sessionStorage.setItem('costguard-oidc-tokens', JSON.stringify({ access, refresh: 'r', expiresAt: Date.now() + 3600_000 }));
}

function core(unmatched: Response) {
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = new URL(input, 'http://localhost');
    if (url.pathname === '/api/v1/payroll/schedule') return new Response(JSON.stringify({ advanceDay: 25, salaryDay: 10 }), { status: 200 });
    if (url.pathname === '/api/v1/payroll/payments') return new Response('[]', { status: 200 });
    if (url.pathname === '/api/v1/payroll/unmatched') return unmatched;
    return new Response(JSON.stringify({ title: 'Not Found' }), { status: 404 });
  }));
}

const show = () => render(<StoreProvider><EmployerPayroll /></StoreProvider>);

beforeEach(() => { sessionStorage.clear(); signIn(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('выплаты без сотрудника', () => {
  it('показывает строку, как её прислал источник, и понятную причину', async () => {
    core(new Response(JSON.stringify([
      { id: 'u1', source: 'excel:vedomost.csv', reportedEmail: 'user@romashka.example', reportedName: null, kind: 'advance', period: '2026-10-01', payDate: '2026-10-24', amount: 31313.13, currency: 'RUB', externalKey: null, reason: 'ambiguous employee', candidates: 2, firstSeen: '2026-11-12T10:00:00Z', lastSeen: '2026-11-12T10:00:00Z' },
      { id: 'u2', source: '1c-zup', reportedEmail: null, reportedName: 'Иванов Иван', kind: 'bonus', period: '2026-10-01', payDate: '2026-10-21', amount: 1000, currency: 'RUB', externalKey: 'zup-1', reason: 'bad email', candidates: 0, firstSeen: '2026-11-12T10:00:00Z', lastSeen: '2026-11-12T10:00:00Z' },
    ]), { status: 200 }));
    show();
    expect(await screen.findByText('user@romashka.example')).toBeTruthy();
    expect(screen.getByText('адрес у нескольких сотрудников, выплата не назначена')).toBeTruthy();
    expect(screen.getByText(/сотрудников с этим адресом: 2/)).toBeTruthy();
    expect(screen.getByText('Иванов Иван')).toBeTruthy();
    expect(screen.getByText('неверный адрес почты')).toBeTruthy();
    expect(screen.getByText('excel:vedomost.csv')).toBeTruthy();
  });

  it('пусто — все выплаты назначены', async () => {
    core(new Response('[]', { status: 200 }));
    show();
    expect(await screen.findByText('Все выплаты назначены')).toBeTruthy();
  });

  it('отказ сервера — сообщение, а не падение страницы', async () => {
    core(new Response(JSON.stringify({ title: 'Forbidden', status: 403, detail: 'Нет доступа', code: 'role_required' }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } }));
    show();
    await waitFor(() => expect(screen.getByText('Загрузить ведомость')).toBeTruthy());
    await waitFor(() => expect(screen.queryByText('Все выплаты назначены')).toBeNull());
    expect(screen.queryByText('user@romashka.example')).toBeNull();
  });
});
