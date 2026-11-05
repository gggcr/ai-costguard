import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useStore } from '../api/store';
import { ROLE_HINT, ROLE_LABEL, useAuth } from '../auth/auth';
import { Button, Icon } from '../components/ui';

export function Login() {
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
