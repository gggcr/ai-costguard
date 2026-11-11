// Вход через Keycloak (realm costguard, клиент costguard-frontend): в режиме API — настоящий OIDC (oidc.ts),
// в демо — выбор демо-пользователя. Роли совпадают с infra/keycloak/costguard-realm.json.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Agent, Me, Role, User } from '../api/types';
import { api } from '../api/http';
import { useStore } from '../api/store';
import * as oidc from './oidc';

// Порядок — старшинство: у человека с ролями owner и employee роль owner
const ROLES: Role[] = ['owner', 'finance', 'it_admin', 'agent_owner', 'auditor', 'employee'];

/**
 * Пользователь из токена: роль — старшая из ролей realm; имя и сотрудник — из GET /api/v1/me, а пока он не
 * ответил — из списка пользователей ядра по почте.
 */
export function userFromClaims(claims: oidc.Claims, users: User[], me?: Me | null): User | null {
  const roles = claims.realm_access?.roles ?? [];
  const role = ROLES.find((r) => roles.includes(r));
  if (!role) return null;
  const email = (claims.email ?? '').toLowerCase();
  const known = users.find((u) => u.email.toLowerCase() === email);
  return {
    id: me?.userId ?? known?.id ?? claims.sub,
    name: me?.name ?? known?.name ?? claims.name ?? claims.preferred_username ?? email,
    email: claims.email ?? known?.email ?? '',
    role,
    employeeId: me?.employee?.id ?? known?.employeeId,
  };
}

export const ROLE_LABEL: Record<Role, string> = {
  owner: 'Владелец',
  finance: 'Финансист',
  it_admin: 'ИТ-администратор',
  agent_owner: 'Владелец агента',
  auditor: 'Аудитор',
  employee: 'Сотрудник',
};

export const ROLE_HINT: Record<Role, string> = {
  owner: 'Видит всё и может всё',
  finance: 'Бюджеты, согласования, подписки, техника, импорт, находки, отчёты',
  it_admin: 'Агенты, мандаты, ключи, подписки, техника, оргструктура',
  agent_owner: 'Свои агенты, их бюджеты и согласования',
  auditor: 'Только чтение, проверка квитанций в NEAR, отчёты',
  employee: 'Только свой кабинет: профиль, документы, отпуска, зарплата, лицензии',
};

export type Section =
  | 'dashboard' | 'agents' | 'policies' | 'budgets' | 'approvals' | 'operations'
  | 'software' | 'assets' | 'imports' | 'connections' | 'findings' | 'analytics' | 'org' | 'notifications' | 'cabinet' | 'people';

const ALL: Section[] = ['dashboard', 'agents', 'policies', 'budgets', 'approvals', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'analytics', 'org', 'notifications', 'people'];

const VIEW: Record<Role, Section[]> = {
  owner: ALL,
  finance: ['dashboard', 'agents', 'budgets', 'approvals', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'analytics', 'notifications', 'people'],
  it_admin: ['dashboard', 'agents', 'policies', 'budgets', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'org', 'notifications', 'people'],
  agent_owner: ['dashboard', 'agents', 'policies', 'budgets', 'approvals', 'operations', 'assets', 'findings', 'notifications'],
  auditor: ['dashboard', 'agents', 'policies', 'budgets', 'operations', 'software', 'assets', 'connections', 'findings', 'analytics', 'notifications'],
  // ни одного раздела с данными компании: ядро всё равно ответит 403 (ADR 0051)
  employee: ['cabinet'],
};

const EDIT: Record<Role, Section[]> = {
  owner: ALL,
  finance: ['budgets', 'approvals', 'software', 'assets', 'imports', 'findings', 'analytics', 'people'],
  // Подключения меняют только owner и it_admin, как @ManagesConnections в ядре
  it_admin: ['agents', 'policies', 'software', 'assets', 'imports', 'connections', 'findings', 'org', 'people'],
  agent_owner: ['agents', 'approvals', 'findings'],
  auditor: ['analytics'],
  employee: ['cabinet'],
};

interface AuthValue {
  user: User | null;
  login: (userId: string) => void;
  logout: () => void;
  canView: (s: Section) => boolean;
  canEdit: (s: Section) => boolean;
  canManageAgent: (a: Agent | undefined) => boolean;
}

const AuthContext = createContext<AuthValue | null>(null);
const KEY = 'costguard-demo-user';

export function AuthProvider({ children }: { children: ReactNode }) {
  const { mode } = useStore();
  return mode === 'api' ? <OidcAuthProvider>{children}</OidcAuthProvider> : <DemoAuthProvider>{children}</DemoAuthProvider>;
}

function access(user: User | null, login: (id: string) => void, logout: () => void): AuthValue {
  return {
    user,
    login,
    logout,
    // «Мой кабинет» есть у каждого, кто сотрудник компании, какой бы ни была его роль
    canView: (s) => !!user && (VIEW[user.role].includes(s) || (s === 'cabinet' && !!user.employeeId)),
    canEdit: (s) => !!user && EDIT[user.role].includes(s),
    canManageAgent: (a) => {
      if (!user || !a) return false;
      if (user.role === 'owner' || user.role === 'it_admin') return true;
      return user.role === 'agent_owner' && a.ownerId === user.employeeId;
    },
  };
}

function OidcAuthProvider({ children }: { children: ReactNode }) {
  const { state } = useStore();
  const [claims, setClaims] = useState(oidc.currentClaims);
  useEffect(() => oidc.onTokensChange(() => setClaims(oidc.currentClaims())), []);
  const [me, setMe] = useState<Me | null>(null);
  useEffect(() => {
    if (!claims) { setMe(null); return; }
    let cancelled = false;
    api<Me>('GET', '/api/v1/me').then((m) => { if (!cancelled) setMe(m); }).catch(() => { if (!cancelled) setMe(null); });
    return () => { cancelled = true; };
  }, [claims]);
  const user = useMemo(() => (claims ? userFromClaims(claims, state.users, me) : null), [claims, state.users, me]);
  const login = useCallback(() => { void oidc.login(window.location.pathname === '/login' ? '/' : window.location.pathname); }, []);
  const logout = useCallback(() => oidc.logout(), []);
  const value = useMemo(() => access(user, login, logout), [user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function DemoAuthProvider({ children }: { children: ReactNode }) {
  const { state } = useStore();
  const [userId, setUserId] = useState<string | null>(() => {
    try { return localStorage.getItem(KEY); } catch { return null; }
  });
  const user = state.users.find((u) => u.id === userId) ?? null;

  const login = useCallback((id: string) => {
    setUserId(id);
    try { localStorage.setItem(KEY, id); } catch { /* приватный режим */ }
  }, []);
  const logout = useCallback(() => {
    setUserId(null);
    try { localStorage.removeItem(KEY); } catch { /* приватный режим */ }
  }, []);

  const value = useMemo(() => access(user, login, logout), [user, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth вне AuthProvider');
  return ctx;
}
