// Имитация входа через Keycloak (realm costguard, клиент costguard-frontend).
// Роли совпадают с infra/keycloak/costguard-realm.json: owner, finance, it_admin, agent_owner, auditor.
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Agent, Role, User } from '../api/types';
import { useStore } from '../api/store';

export const ROLE_LABEL: Record<Role, string> = {
  owner: 'Владелец',
  finance: 'Финансист',
  it_admin: 'ИТ-администратор',
  agent_owner: 'Владелец агента',
  auditor: 'Аудитор',
};

export const ROLE_HINT: Record<Role, string> = {
  owner: 'Видит всё и может всё',
  finance: 'Бюджеты, согласования, подписки, техника, импорт, находки, отчёты',
  it_admin: 'Агенты, мандаты, ключи, подписки, техника, оргструктура',
  agent_owner: 'Свои агенты, их бюджеты и согласования',
  auditor: 'Только чтение, проверка квитанций в NEAR, отчёты',
};

export type Section =
  | 'dashboard' | 'agents' | 'policies' | 'budgets' | 'approvals' | 'operations'
  | 'software' | 'assets' | 'imports' | 'connections' | 'findings' | 'analytics' | 'org' | 'notifications';

const ALL: Section[] = ['dashboard', 'agents', 'policies', 'budgets', 'approvals', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'analytics', 'org', 'notifications'];

const VIEW: Record<Role, Section[]> = {
  owner: ALL,
  finance: ['dashboard', 'agents', 'budgets', 'approvals', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'analytics', 'notifications'],
  it_admin: ['dashboard', 'agents', 'policies', 'budgets', 'operations', 'software', 'assets', 'imports', 'connections', 'findings', 'org', 'notifications'],
  agent_owner: ['dashboard', 'agents', 'policies', 'budgets', 'approvals', 'operations', 'assets', 'findings', 'notifications'],
  auditor: ['dashboard', 'agents', 'policies', 'budgets', 'operations', 'software', 'assets', 'connections', 'findings', 'analytics', 'notifications'],
};

const EDIT: Record<Role, Section[]> = {
  owner: ALL,
  finance: ['budgets', 'approvals', 'software', 'assets', 'imports', 'findings', 'analytics'],
  // Подключения меняют только owner и it_admin, как @ManagesConnections в ядре
  it_admin: ['agents', 'policies', 'software', 'assets', 'imports', 'connections', 'findings', 'org'],
  agent_owner: ['agents', 'approvals', 'findings'],
  auditor: ['analytics'],
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

  const value = useMemo<AuthValue>(() => ({
    user,
    login,
    logout,
    canView: (s) => !!user && VIEW[user.role].includes(s),
    canEdit: (s) => !!user && EDIT[user.role].includes(s),
    canManageAgent: (a) => {
      if (!user || !a) return false;
      if (user.role === 'owner' || user.role === 'it_admin') return true;
      return user.role === 'agent_owner' && a.ownerId === user.employeeId;
    },
  }), [user, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth вне AuthProvider');
  return ctx;
}
