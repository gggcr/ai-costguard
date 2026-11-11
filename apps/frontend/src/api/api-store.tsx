// Хранилище в режиме API: состояние читается из ядра, действия — запросы к нему (remote-actions.ts),
// новые уведомления приходят потоком SSE и перечитывают затронутые разделы.
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { STAFF_ROLES, type AppState, type Notification, type SystemType } from './types';
import { EMPTY_STATE, LOADERS, type Slice } from './remote';
import { remoteActions } from './remote-actions';
import { ApiError, subscribe } from './http';
import { StoreContext, type Actions, type StoreValue, type Toast } from './store';
import { currentClaims, onTokensChange } from '../auth/oidc';
import { newId } from '../lib';

const ALL: Slice[] = ['company', 'users', 'departments', 'employees', 'agents', 'mandates', 'budgets', 'operations', 'approvals', 'vendors',
  'contracts', 'subscriptions', 'assets', 'findings', 'imports', 'notifications', 'costHistory', 'connections', 'systemTypes'];

const SLICE_TITLE: Partial<Record<Slice, string>> = {
  operations: 'Журнал операций', costHistory: 'Расходы по месяцам', connections: 'Подключённые системы', assets: 'Техника',
  subscriptions: 'Подписки', findings: 'Находки', imports: 'Импорт', notifications: 'Уведомления', agents: 'Агенты', budgets: 'Бюджеты',
};

// Что могло измениться, когда ядро присылает уведомление: по ссылке уведомления
function slicesFor(link: string | undefined): Slice[] {
  const base: Slice[] = ['notifications'];
  if (!link) return [...base, 'findings', 'approvals'];
  if (link.startsWith('/approvals')) return [...base, 'approvals', 'operations', 'budgets'];
  if (link.startsWith('/agents')) return [...base, 'agents', 'operations'];
  if (link.startsWith('/findings')) return [...base, 'findings'];
  if (link.startsWith('/imports')) return [...base, 'imports'];
  if (link.startsWith('/assets')) return [...base, 'assets', 'findings'];
  if (link.startsWith('/connections')) return [...base, 'connections'];
  if (link.startsWith('/operations') || link.startsWith('/budgets')) return [...base, 'operations', 'budgets'];
  return [...base, 'findings'];
}

export function ApiStoreProvider({ children }: { children: ReactNode }) {
  const [state, setRaw] = useState<AppState>(EMPTY_STATE);
  const [systemTypes, setSystemTypes] = useState<SystemType[]>([]);
  const [company, setCompany] = useState({ name: '', currency: 'RUB' });
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [live, setLive] = useState(true);
  const [signedIn, setSignedIn] = useState(() => currentClaims() !== null);
  const [loading, setLoading] = useState(signedIn);
  const [error, setError] = useState<string | null>(null);

  const stateRef = useRef(state);
  stateRef.current = state;
  const companyRef = useRef(company);
  companyRef.current = company;

  useEffect(() => onTokensChange(() => setSignedIn(currentClaims() !== null)), []);
  // Только «Мой кабинет»: роль employee без ролей персонала не читает данные компании (ядро ответит 403, ADR 0051)
  const staff = signedIn && (currentClaims()?.realm_access?.roles ?? []).some((r) => (STAFF_ROLES as string[]).includes(r));

  const dismissToast = useCallback((id: string) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const toast = useCallback((text: string, level: Notification['level'] = 'info') => {
    const id = newId('t');
    setToasts((t) => [...t.slice(-3), { id, text, level }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);

  const patch = useCallback((fn: (s: AppState) => void) => {
    setRaw((prev) => {
      const next = structuredClone(prev);
      fn(next);
      return next;
    });
  }, []);

  /**
   * Перечитать разделы. Раздел, к которому у роли нет доступа (403), остаётся пустым; раздел, который не загрузился
   * (например, журнал операций без ClickHouse), — прежним, с сообщением. Бросает только 401 и полный отказ.
   */
  const reload = useCallback(async (slices: Slice[]) => {
    const unique = [...new Set(slices)];
    const failed: { slice: Slice; error: unknown }[] = [];
    const results = await Promise.all(unique.map(async (slice) => {
      const load = LOADERS[slice];
      if (!load) return {};
      try {
        return await load(stateRef.current);
      } catch (e) {
        if (e instanceof ApiError && (e.status === 403 || e.status === 404)) return {};
        failed.push({ slice, error: e });
        return {};
      }
    }));
    const merged = Object.assign({}, ...results) as Partial<AppState> & { systemTypes?: SystemType[]; company?: { name: string; currency: string } };
    const { systemTypes: types, company: c, ...data } = merged;
    if (types) setSystemTypes(types);
    if (c) setCompany(c);
    setRaw((prev) => ({ ...prev, ...data }));
    const unauthenticated = failed.find((f) => f.error instanceof ApiError && f.error.status === 401);
    if (unauthenticated) throw unauthenticated.error;
    if (failed.length && failed.length === unique.length) throw failed[0].error;
    failed.forEach(({ slice, error }) => toast(`${SLICE_TITLE[slice] ?? slice}: ${error instanceof ApiError ? error.message : 'нет связи с сервером'}`, 'warning'));
  }, [toast]);

  // Первая загрузка после входа
  useEffect(() => {
    if (!signedIn) {
      setRaw(EMPTY_STATE);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    reload(staff ? ALL : [])
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof ApiError ? e.message : 'Сервер CostGuard не отвечает'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [signedIn, staff, reload]);

  // Поток уведомлений ядра: новое уведомление — сообщение и перечитывание связанных разделов
  useEffect(() => {
    if (!signedIn || !live || !staff) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const pending = new Set<Slice>();
    return subscribe('/api/v1/notifications/stream', (name, data) => {
      if (name !== 'notification') return;
      try {
        const n = JSON.parse(data) as { text?: string; level?: Notification['level']; link?: string };
        if (n.text) toast(n.text, n.level ?? 'info');
        slicesFor(n.link).forEach((x) => pending.add(x));
      } catch {
        pending.add('notifications');
      }
      clearTimeout(timer);
      timer = setTimeout(() => { void reload([...pending]).catch(() => undefined); pending.clear(); }, 800);
    });
  }, [signedIn, live, staff, reload, toast]);

  // Решения шлюза без уведомлений (обычные разрешённые операции) — перечитываем журнал раз в 30 секунд
  useEffect(() => {
    if (!signedIn || !live || !staff) return;
    const t = setInterval(() => { void reload(['operations', 'budgets']).catch(() => undefined); }, 30_000);
    return () => clearInterval(t);
  }, [signedIn, live, staff, reload]);

  const actions = useMemo(() => remoteActions({
    getState: () => stateRef.current,
    currency: () => companyRef.current.currency,
    reload,
    patch,
    toast,
  }) as unknown as Actions, [reload, patch, toast]);

  const value = useMemo<StoreValue>(() => ({
    state, actions, toasts, dismissToast, toast, live, setLive, mode: 'api', loading, error, systemTypes, company,
    reloadOrg: () => reload(['users', 'departments', 'employees']),
  }), [state, actions, toasts, dismissToast, toast, live, loading, error, systemTypes, company, reload]);

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}
