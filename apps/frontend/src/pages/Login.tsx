import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useStore } from '../api/store';
import { ROLE_HINT, ROLE_LABEL, useAuth } from '../auth/auth';
import { completeLogin, currentClaims, login as startLogin } from '../auth/oidc';
import { api } from '../api/http';
import { Button, Icon } from '../components/ui';

export function Login() {
  const { mode } = useStore();
  return mode === 'api' ? <KeycloakLogin /> : <DemoLogin />;
}

/** Вход через Keycloak: кнопка ведёт на страницу входа realm costguard, Keycloak возвращает сюда с ?code=. */
function KeycloakLogin() {
  const { user } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [checking, setChecking] = useState(false);
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(() => new URLSearchParams(location.search).has('code'));
  const started = useRef(false);

  useEffect(() => {
    if (started.current || !new URLSearchParams(location.search).has('code')) return;
    started.current = true;
    completeLogin(location.search)
      .then((to) => navigate(to ?? '/', { replace: true }))
      .catch((e: unknown) => { setError(e instanceof Error ? e.message : 'Не удалось войти'); setBusy(false); });
  }, [location.search, navigate]);

  /** Почта ведёт к Microsoft 365 компании, если она его подключила; иначе — обычная форма Keycloak. */
  const next = async () => {
    setChecking(true);
    let idp: string | undefined;
    try {
      idp = (await api<{ alias: string }>('GET', '/api/v1/login/hint', undefined, { email: email.trim() })).alias;
    } catch {
      idp = undefined;
    }
    await startLogin('/', { idp, email: email.trim() });
  };

  if (user) return <Navigate to="/" replace />;
  const noRole = !busy && !error && currentClaims() !== null;

  return (
    <div className="login">
      <div className="login-card">
        <div className="brand login-brand">
          <span className="brand-mark"><Icon name="policies" size={20} /></span>
          <span>AI CostGuard</span>
        </div>
        <h1>Вход</h1>
        {busy && <p className="muted">Проверяем вход…</p>}
        {error && <p className="error-text" role="alert">{error}</p>}
        {noRole && <p className="error-text" role="alert">У вашей учётной записи нет роли CostGuard. Попросите владельца компании выдать доступ.</p>}
        {!busy && <form onSubmit={(e) => { e.preventDefault(); void next(); }}>
          <p className="muted">Введите рабочую почту. Если компания подключила Microsoft 365, вы войдёте рабочей учётной записью. Затем — код из Google Authenticator (при первом входе его нужно настроить).</p>
          <label className="field"><span className="field-label">Рабочая почта</span>
            <input className="input" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ivanov@company.ru" />
          </label>
          <Button variant="primary" className="block-btn" type="submit" disabled={checking || !email.includes('@')}>{checking ? 'Проверяем…' : 'Продолжить'}</Button>
          <Button variant="ghost" className="block-btn" type="button" onClick={() => { void startLogin('/', { email: email || undefined }); }}>Войти по логину и паролю</Button>
        </form>}
      </div>
    </div>
  );
}

function DemoLogin() {
  const { state } = useStore();
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [selected, setSelected] = useState(state.users[0]?.id ?? '');
  if (user) return <Navigate to="/" replace />;

  return (
    <div className="login">
      <div className="login-card">
        <div className="brand login-brand">
          <span className="brand-mark"><Icon name="policies" size={20} /></span>
          <span>AI CostGuard</span>
        </div>
        <h1>Вход</h1>
        <p className="muted">В рабочей версии здесь будет перенаправление на Keycloak (realm <code>costguard</code>). В прототипе выберите демо-пользователя: от роли зависят меню и доступные действия.</p>
        <div className="role-list" role="radiogroup" aria-label="Демо-пользователь">
          {state.users.map((u) => (
            <label key={u.id} className={`role-option ${selected === u.id ? 'selected' : ''}`}>
              <input type="radio" name="user" value={u.id} checked={selected === u.id} onChange={() => setSelected(u.id)} />
              <span>
                <strong>{u.name}</strong> <span className="muted">· {ROLE_LABEL[u.role]}</span>
                <span className="muted small block">{ROLE_HINT[u.role]}</span>
              </span>
            </label>
          ))}
        </div>
        <Button variant="primary" className="block-btn" onClick={() => { login(selected); navigate('/'); }} disabled={!selected}>
          Войти через Keycloak (демо)
        </Button>
      </div>
    </div>
  );
}
