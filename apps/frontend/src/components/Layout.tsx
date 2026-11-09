// Каркас приложения: боковое меню, верхняя панель, поиск, уведомления, смена пользователя
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useStore } from '../api/store';
import { ROLE_LABEL, useAuth, type Section } from '../auth/auth';
import { Forbidden, Icon, Toasts } from './ui';
import { fmtRelative } from '../lib';

const NAV: { section: Section; to: string; label: string; group: string }[] = [
  { section: 'dashboard', to: '/', label: 'Обзор', group: '' },
  { section: 'agents', to: '/agents', label: 'ИИ-агенты', group: 'Контур А: ИИ-агенты' },
  { section: 'policies', to: '/policies', label: 'Мандаты и правила', group: 'Контур А: ИИ-агенты' },
  { section: 'approvals', to: '/approvals', label: 'Согласования', group: 'Контур А: ИИ-агенты' },
  { section: 'operations', to: '/operations', label: 'Журнал операций', group: 'Контур А: ИИ-агенты' },
  { section: 'budgets', to: '/budgets', label: 'Бюджеты', group: 'Контур А: ИИ-агенты' },
  { section: 'software', to: '/software', label: 'Подписки и договоры', group: 'Контур Б: расходы на ПО' },
  { section: 'assets', to: '/assets', label: 'Техника сотрудников', group: 'Контур Б: расходы на ПО' },
  { section: 'imports', to: '/imports', label: 'Импорт данных', group: 'Контур Б: расходы на ПО' },
  { section: 'connections', to: '/connections', label: 'Подключённые системы', group: 'Контур Б: расходы на ПО' },
  { section: 'findings', to: '/findings', label: 'Находки', group: 'Контур Б: расходы на ПО' },
  { section: 'analytics', to: '/analytics', label: 'Прогнозы и отчёты', group: 'Контур Б: расходы на ПО' },
  { section: 'org', to: '/org', label: 'Оргструктура и доступ', group: 'Настройки' },
];

export function Guard({ section, children }: { section: Section; children: ReactNode }) {
  const { canView } = useAuth();
  return canView(section) ? <>{children}</> : <Forbidden />;
}

function useClickOutside(onOutside: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const h = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onOutside(); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [onOutside]);
  return ref;
}

function GlobalSearch() {
  const { state } = useStore();
  const { canView } = useAuth();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const ref = useClickOutside(() => setOpen(false));

  const results = useMemo(() => {
    const t = q.trim().toLowerCase();
    if (t.length < 2) return [];
    const out: { label: string; kind: string; to: string }[] = [];
    if (canView('agents')) state.agents.filter((a) => a.name.toLowerCase().includes(t)).forEach((a) => out.push({ label: a.name, kind: 'Агент', to: `/agents/${a.id}` }));
    if (canView('software')) state.subscriptions.filter((s) => s.product.toLowerCase().includes(t)).forEach((s) => out.push({ label: s.product, kind: 'Подписка', to: `/software/subscriptions/${s.id}` }));
    if (canView('assets')) state.assets.filter((a) => [a.model, a.inventoryNumber, a.serialNumber ?? ''].some((x) => x.toLowerCase().includes(t))).forEach((a) => out.push({ label: `${a.model} · ${a.inventoryNumber}`, kind: 'Техника', to: `/assets/${a.id}` }));
    if (canView('findings')) state.findings.filter((f) => f.title.toLowerCase().includes(t)).forEach((f) => out.push({ label: f.title, kind: 'Находка', to: `/findings/${f.id}` }));
    if (canView('operations')) state.operations.filter((o) => o.id.toLowerCase().includes(t)).slice(0, 3).forEach((o) => out.push({ label: o.id, kind: 'Операция', to: `/operations/${o.id}` }));
    if (canView('org')) state.employees.filter((e) => e.name.toLowerCase().includes(t)).forEach((e) => out.push({ label: e.name, kind: 'Сотрудник', to: `/org?employee=${e.id}` }));
    return out.slice(0, 8);
  }, [q, state, canView]);

  const go = (to: string) => { navigate(to); setQ(''); setOpen(false); };

  return (
    <div className="search" ref={ref}>
      <Icon name="search" size={16} />
      <input
        value={q}
        onChange={(e) => { setQ(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => { if (e.key === 'Enter' && results[0]) go(results[0].to); if (e.key === 'Escape') setOpen(false); }}
        placeholder="Поиск: агенты, подписки, находки…"
        aria-label="Поиск"
      />
      {open && q.trim().length >= 2 && (
        <div className="dropdown search-results">
          {results.length === 0 && <div className="dropdown-empty">Ничего не найдено</div>}
          {results.map((r) => (
            <button key={r.kind + r.to} className="dropdown-item" onClick={() => go(r.to)}>
              <span className="muted small">{r.kind}</span> {r.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function NotificationsBell() {
  const { state, actions } = useStore();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useClickOutside(() => setOpen(false));
  const unread = state.notifications.filter((n) => !n.read).length;
  return (
    <div className="menu-wrap" ref={ref}>
      <button className="icon-btn bell" onClick={() => setOpen((v) => !v)} aria-label={`Уведомления, непрочитанных ${unread}`}>
        <Icon name="notifications" />
        {unread > 0 && <span className="bell-count">{unread}</span>}
      </button>
      {open && (
        <div className="dropdown notif-dropdown">
          <div className="dropdown-head">
            <strong>Уведомления</strong>
            <button className="link-btn" onClick={() => actions.markAllRead()}>Прочитать все</button>
          </div>
          {state.notifications.length === 0 && <div className="dropdown-empty">Пусто</div>}
          {state.notifications.slice(0, 6).map((n) => (
            <button key={n.id} className={`dropdown-item notif ${n.read ? '' : 'unread'}`} onClick={() => { actions.markRead(n.id); setOpen(false); if (n.link) navigate(n.link); }}>
              <span className={`dot dot-${n.level}`} aria-hidden="true" />
              <span>
                <span className="notif-text">{n.text}</span>
                <span className="muted small">{fmtRelative(n.at)}</span>
              </span>
            </button>
          ))}
          <button className="dropdown-foot" onClick={() => { setOpen(false); navigate('/notifications'); }}>Все уведомления →</button>
        </div>
      )}
    </div>
  );
}

function UserMenu() {
  const { state, actions } = useStore();
  const { user, login, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useClickOutside(() => setOpen(false));
  if (!user) return null;
  return (
    <div className="menu-wrap" ref={ref}>
      <button className="user-btn" onClick={() => setOpen((v) => !v)} aria-haspopup="menu">
        <span className="avatar">{user.name.split(' ').map((p) => p[0]).join('')}</span>
        <span className="user-meta">
          <span>{user.name}</span>
          <span className="muted small">{ROLE_LABEL[user.role]}</span>
        </span>
      </button>
      {open && (
        <div className="dropdown user-dropdown" role="menu">
          <div className="dropdown-head"><strong>Войти как другая роль</strong></div>
          {state.users.map((u) => (
            <button key={u.id} className={`dropdown-item ${u.id === user.id ? 'current' : ''}`} onClick={() => { login(u.id); setOpen(false); navigate('/'); }}>
              {u.name} <span className="muted small">· {ROLE_LABEL[u.role]}</span>
            </button>
          ))}
          <div className="dropdown-sep" />
          <button className="dropdown-item" onClick={() => { actions.reset(); setOpen(false); }}>
            <Icon name="refresh" size={14} /> Сбросить демо-данные
          </button>
          <button className="dropdown-item" onClick={() => { logout(); navigate('/login'); }}>
            <Icon name="logout" size={14} /> Выйти
          </button>
        </div>
      )}
    </div>
  );
}

export function Layout() {
  const { state, live, setLive } = useStore();
  const { canView } = useAuth();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => setMenuOpen(false), [location.pathname]);

  const pendingApprovals = state.approvals.filter((a) => a.status === 'pending').length;
  const newFindings = state.findings.filter((f) => f.status === 'new').length;
  const counts: Partial<Record<Section, number>> = { approvals: pendingApprovals, findings: newFindings };

  let lastGroup = '';
  return (
    <div className={`app ${menuOpen ? 'menu-open' : ''}`}>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><Icon name="policies" size={18} /></span>
          <span>AI CostGuard</span>
        </div>
        <nav aria-label="Основное меню">
          {NAV.filter((n) => canView(n.section)).map((n) => {
            const header = n.group && n.group !== lastGroup ? <div className="nav-group" key={`g-${n.group}`}>{n.group}</div> : null;
            lastGroup = n.group;
            return (
              <div key={n.to}>
                {header}
                <NavLink to={n.to} end={n.to === '/'} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                  <Icon name={n.section} />
                  <span>{n.label}</span>
                  {!!counts[n.section] && <span className="nav-count">{counts[n.section]}</span>}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <div className="sidebar-foot muted small">Демо-компания «Северный ветер»<br />Прототип, данные синтетические</div>
      </aside>
      <div className="sidebar-scrim" onClick={() => setMenuOpen(false)} />
      <div className="main">
        <header className="topbar">
          <button className="icon-btn burger" onClick={() => setMenuOpen((v) => !v)} aria-label="Меню"><Icon name="menu" /></button>
          <GlobalSearch />
          <div className="topbar-right">
            <button className={`live-toggle ${live ? 'on' : ''}`} onClick={() => setLive(!live)} title="Имитация событий SSE от ядра: новые операции агентов, согласования">
              <span className="live-dot" aria-hidden="true" /> {live ? 'Поток событий: вкл' : 'Поток событий: выкл'}
            </button>
            <NotificationsBell />
            <UserMenu />
          </div>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
      <Toasts />
    </div>
  );
}
