// @vitest-environment jsdom
// Режим API: ответы ядра → состояние страниц, действия → запросы, ошибки ядра → сообщения, поток SSE.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { useContext } from 'react';
import { ApiStoreProvider } from './api-store';
import { StoreContext, type StoreValue } from './store';
import { toOperation, type ApiOperation } from './remote';
import { ApiError, api, subscribe } from './http';
import { userFromClaims } from '../auth/auth';
import { ChannelSettings } from '../pages/ChannelSettings';
import { fireEvent } from '@testing-library/react';

const TENANT = '0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a';

function jwt(claims: object): string {
  // base64url от UTF-8, как в настоящем токене (в имени кириллица)
  const b = (o: object) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b({ alg: 'none' })}.${b(claims)}.sig`;
}

function signIn() {
  const access = jwt({ sub: 'kc-1', email: 'ivanov@romashka.example', name: 'Иванов Иван', tenant_id: TENANT, exp: Date.now() / 1000 + 3600, realm_access: { roles: ['owner'] } });
  sessionStorage.setItem('costguard-oidc-tokens', JSON.stringify({ access, refresh: 'r', expiresAt: Date.now() + 3600_000 }));
}

type Handler = (method: string, url: URL, body: unknown) => unknown;
const calls: { method: string; path: string; body: unknown }[] = [];

function mockCore(extra: Handler = () => undefined) {
  const data: Record<string, unknown> = {
    '/api/v1/company': { name: 'ООО «Ромашка»', currency: 'RUB' },
    '/api/v1/users': [{ id: 'u1', name: 'Иванов Иван', email: 'ivanov@romashka.example', role: 'owner', employeeId: 'e1' }],
    '/api/v1/departments': [{ id: 'd1', name: 'ИТ' }],
    '/api/v1/employees': [{ id: 'e1', name: 'Иванов Иван', email: 'ivanov@romashka.example', departmentId: 'd1', position: 'Руководитель ИТ', active: true }],
    '/api/v1/agents': [{ id: 'a1', name: 'Поддержка', description: '', framework: 'LangChain', departmentId: 'd1', ownerId: 'e1', status: 'active', riskScore: 12, mandateId: 'm1', createdAt: '2026-11-01T00:00:00Z' }],
    '/api/v1/agents/a1/keys': [{ id: 'k1', agentId: 'a1', prefix: 'cg_live_ab', createdAt: '2026-11-01T00:00:00Z', status: 'active' }],
    '/api/v1/mandates': [],
    '/api/v1/budgets': [{ id: 'b0', name: 'Компания', level: 'company', limit: 100000, spent: 10 }, { id: 'b1', name: 'ИТ', level: 'department', refId: 'd1', parentId: 'b0', limit: 50000, spent: 10 }],
    '/api/v1/operations': [{ id: 'o1', agentId: 'a1', at: '2026-11-10T10:00:00Z', kind: 'LLM_CALL', provider: 'OpenAI', model: 'gpt-5', decision: 'DENIED', denialCode: 'budget_exceeded', estimatedCost: 3, cost: null, currency: 'RUB', receipt: null }],
    '/api/v1/approvals': [],
    '/api/v1/vendors': [], '/api/v1/contracts': [], '/api/v1/subscriptions': [], '/api/v1/findings': [], '/api/v1/imports': [],
    '/api/v1/assets': { items: [], page: 0, size: 200 },
    '/api/v1/notifications': { items: [{ id: 'n1', at: '2026-11-10T10:00:00Z', text: 'Привет', level: 'info', read: false }], unread: 1 },
    '/api/v1/analytics/summary': { agentSpend: [{ month: '2026-10', amount: 100 }, { month: '2026-11', amount: 150 }], findings: {} },
    '/api/v1/connections': [], '/api/v1/connections/types': [{ type: 'onec', title: '1С', kind: 'accounting', required: ['url'], optional: [], secrets: ['ONEC_PASSWORD'] }],
  };
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input, 'http://localhost');
    const method = init?.method ?? 'GET';
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    calls.push({ method, path: url.pathname, body });
    expect((init?.headers as Record<string, string>).Authorization).toMatch(/^Bearer /);
    if (url.pathname === '/api/v1/notifications/stream') return new Promise(() => undefined); // поток висит открытым
    const custom = extra(method, url, body);
    if (custom instanceof Response) return custom;
    if (custom !== undefined) return new Response(JSON.stringify(custom), { status: 200 });
    if (method !== 'GET') return new Response(null, { status: 204 });
    if (url.pathname in data) return new Response(JSON.stringify(data[url.pathname]), { status: 200 });
    return new Response(JSON.stringify({ title: 'Not Found' }), { status: 404 });
  }));
}

let store: StoreValue;
function Probe() {
  store = useContext(StoreContext)!;
  return <div>{store.loading ? 'loading' : `agents:${store.state.agents.length}`}</div>;
}

beforeEach(() => { calls.length = 0; sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('ответы ядра → состояние', () => {
  it('отказ шлюза по бюджету — код 402 и ступень 5', () => {
    const op = toOperation({ id: 'o', agentId: 'a', at: 't', kind: 'LLM_CALL', provider: 'OpenAI', decision: 'DENIED', denialCode: 'budget_exceeded', estimatedCost: 5, currency: 'RUB' } as ApiOperation);
    expect(op).toMatchObject({ code: '402_budget_exceeded', failedStep: 5, cost: 0, resource: 'LLM', receipt: undefined });
  });

  it('разрешённая операция списывает факт и несёт статус квитанции', () => {
    const op = toOperation({ id: 'o', agentId: 'a', at: 't', kind: 'PAID_API', provider: 'Dadata', decision: 'ALLOWED', estimatedCost: 5, cost: 4.2, currency: 'RUB', receipt: 'anchored' });
    expect(op).toMatchObject({ code: 'allowed', cost: 4.2, resource: 'Платный API', receipt: { status: 'anchored' } });
  });

  it('пользователь из токена: старшая роль, сотрудник по почте, без роли — не пускаем', () => {
    const users = [{ id: 'u1', name: 'Иванов', email: 'IVANOV@romashka.example', role: 'auditor' as const, employeeId: 'e1' }];
    expect(userFromClaims({ sub: 's', exp: 0, email: 'ivanov@romashka.example', realm_access: { roles: ['finance', 'auditor'] } }, users))
      .toMatchObject({ id: 'u1', role: 'finance', employeeId: 'e1' });
    expect(userFromClaims({ sub: 's', exp: 0, realm_access: { roles: ['offline_access'] } }, users)).toBeNull();
  });
});

describe('http', () => {
  it('ошибка ядра (problem+json) становится понятным сообщением с кодом', async () => {
    signIn();
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ title: 'Conflict', detail: 'Инвентарный номер уже занят', code: 'inventory_number_taken' }), { status: 409 })));
    const err = await api('POST', '/api/v1/assets', {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: 'inventory_number_taken', message: 'Инвентарный номер уже занят' });
  });

  it('без входа запрос не уходит', async () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    await expect(api('GET', '/api/v1/agents')).rejects.toMatchObject({ status: 401 });
    expect(f).not.toHaveBeenCalled();
  });

  it('SSE: события собираются из кусков потока', async () => {
    signIn();
    const chunks = ['event: notification\ndata: {"text":"a"', '}\n\nevent: notification\r\ndata: {"text":"b"}\r\n\r\n'];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream({
      start(c) { chunks.forEach((x) => c.enqueue(new TextEncoder().encode(x))); c.close(); },
    }), { status: 200 })));
    const got: string[] = [];
    const stop = subscribe('/api/v1/notifications/stream', (name, data) => got.push(`${name}:${JSON.parse(data).text}`));
    await waitFor(() => expect(got).toEqual(['notification:a', 'notification:b']));
    stop();
  });
});

describe('хранилище в режиме API', () => {
  it('без входа ничего не грузит', async () => {
    mockCore();
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    expect(screen.getByText('agents:0')).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it('после входа читает разделы из ядра', async () => {
    signIn();
    mockCore();
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    await screen.findByText('agents:1');
    expect(store.state.keys).toHaveLength(1);
    expect(store.state.operations[0]).toMatchObject({ code: '402_budget_exceeded' });
    expect(store.state.costHistory).toEqual([{ month: '2026-10', agents: 100, software: 0 }, { month: '2026-11', agents: 150, software: 0 }]);
    expect(store.company).toEqual({ name: 'ООО «Ромашка»', currency: 'RUB' });
    expect(store.systemTypes[0].secrets).toEqual(['ONEC_PASSWORD']);
    expect(store.state.notifications[0].text).toBe('Привет');
  });

  it('новый агент: агент, затем бюджет под бюджетом его отдела', async () => {
    signIn();
    mockCore((method, url) => {
      if (method === 'POST' && url.pathname === '/api/v1/agents') return { id: 'a2', name: 'Новый' };
      if (method === 'POST' && url.pathname === '/api/v1/budgets') return { id: 'b2' };
      return undefined;
    });
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    await screen.findByText('agents:1');
    let id: unknown;
    await act(async () => { id = await store.actions.createAgent({ name: 'Новый', description: '', framework: 'CrewAI', departmentId: 'd1', ownerId: 'e1', mandateId: 'm1' }, 5000); });
    expect(id).toBe('a2');
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.map((c) => c.path)).toEqual(['/api/v1/agents', '/api/v1/budgets']);
    expect(posts[1].body).toEqual({ name: 'Новый', level: 'agent', refId: 'a2', parentId: 'b1', limit: 5000, currency: 'RUB' });
  });

  it('отложить находку: дата уходит без времени', async () => {
    signIn();
    mockCore();
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    await screen.findByText('agents:1');
    await act(async () => { await store.actions.findingAction('f1', 'postpone', 'я', { date: '2026-12-01T00:00:00.000Z' }); });
    expect(calls.find((c) => c.path === '/api/v1/findings/f1/actions')?.body).toEqual({ action: 'postpone', date: '2026-12-01' });
  });

  it('ошибка ядра в действии — сообщение, а не падение', async () => {
    signIn();
    mockCore((method, url) => method === 'POST' && url.pathname === '/api/v1/assets'
      ? new Response(JSON.stringify({ detail: 'Инвентарный номер уже занят', code: 'inventory_number_taken' }), { status: 409 }) : undefined);
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    await screen.findByText('agents:1');
    let id: unknown = 'x';
    await act(async () => { id = await store.actions.createAsset({ inventoryNumber: 'INV-1', model: 'T14', type: 'LAPTOP', cost: 1 }); });
    expect(id).toBeNull();
    expect(store.toasts.at(-1)).toMatchObject({ text: 'Инвентарный номер уже занят', level: 'critical' });
  });

  it('раздел, который не загрузился, не роняет приложение: остальное на месте, сообщение есть', async () => {
    signIn();
    mockCore((_method, url) => url.pathname === '/api/v1/operations'
      ? new Response(JSON.stringify({ detail: 'Журнал операций не отвечает', code: 'operations_unavailable' }), { status: 503 }) : undefined);
    render(<ApiStoreProvider><Probe /></ApiStoreProvider>);
    await screen.findByText('agents:1');
    expect(store.error).toBeNull();
    expect(store.state.operations).toEqual([]);
    expect(store.toasts.map((t) => t.text)).toContain('Журнал операций: Журнал операций не отвечает');
  });
});

describe('каналы уведомлений', () => {
  it('уровни сохраняются, Telegram подключается ссылкой и показывает, что подключён', async () => {
    signIn();
    let linked = false;
    let saved: unknown;
    const view = () => ({ email: 'ivanov@romashka.example', emailLevel: 'critical', emailAvailable: true, telegramLevel: 'warning', telegramLinked: linked, telegramAvailable: true });
    mockCore((method, url, body) => {
      if (url.pathname === '/api/v1/notifications/settings' && method === 'GET') return view();
      if (url.pathname === '/api/v1/notifications/settings' && method === 'PUT') { saved = body; return { ...view(), ...(body as object) }; }
      if (url.pathname === '/api/v1/notifications/telegram-link') { linked = true; return { url: 'https://t.me/cg_bot?start=abc', expiresAt: '2026-11-10T12:00:00Z' }; }
      return undefined;
    });
    const open = vi.fn();
    vi.stubGlobal('open', open);
    render(<ApiStoreProvider><ChannelSettings /></ApiStoreProvider>);
    const email = await screen.findByLabelText('Уровень для почты');
    expect((email as HTMLSelectElement).value).toBe('critical');
    fireEvent.change(email, { target: { value: 'info' } });
    await waitFor(() => expect(saved).toEqual({ emailLevel: 'info', telegramLevel: 'warning' }));

    fireEvent.click(screen.getByText('Подключить Telegram'));
    await waitFor(() => expect(open).toHaveBeenCalledWith('https://t.me/cg_bot?start=abc', '_blank', 'noopener'));
    expect(await screen.findByText(/Откройте бота/)).toBeTruthy();
    await screen.findByText(/Telegram подключён/, undefined, { timeout: 5000 });
  });

  it('пользователь без учётной записи в компании видит понятное сообщение', async () => {
    signIn();
    mockCore((_method, url) => url.pathname === '/api/v1/notifications/settings'
      ? new Response(JSON.stringify({ code: 'user_not_found' }), { status: 404 }) : undefined);
    render(<ApiStoreProvider><ChannelSettings /></ApiStoreProvider>);
    expect(await screen.findByText(/не найдена среди пользователей/)).toBeTruthy();
  });
});
