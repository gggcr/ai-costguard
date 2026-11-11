// Свой способ входа (ADR 0059): подключить Google Authenticator и входить кодом вместо пароля.
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, Empty } from '../../components/ui';
import { errorText, useApi } from './useApi';

interface SignIn { otp: boolean; otpFirst: boolean; setupUrl: string }

export function SignInCard() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<SignIn>('/api/v1/me/sign-in');
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const first = async () => {
    try { await api('PUT', '/api/v1/me/sign-in/otp-first'); toast('Теперь при входе сначала спрашивается код', 'good'); reload(); } catch (e) { toast(errorText(e), 'critical'); }
  };
  return (
    <Card title="Вход по коду из Google Authenticator">
      <p className="muted small">Подключите Google Authenticator (или любое приложение кодов) на своём телефоне — к приложению можно привязать любую удобную вам почту, CostGuard это не важно. После этого входите так: рабочая почта, затем 6-значный код вместо пароля. Пароль остаётся запасным способом («Попробовать другой способ»).</p>
      <p>{data.otp ? <Badge tone="good">Приложение подключено</Badge> : <Badge>Не подключено</Badge>} {data.otpFirst && <Badge tone="info">Код вместо пароля</Badge>}</p>
      <Button onClick={() => window.open(data.setupUrl, '_blank', 'noopener')}>{data.otp ? 'Управлять приложением' : 'Подключить Google Authenticator'}</Button>
      {data.otp && !data.otpFirst && <Button variant="primary" onClick={() => { void first(); }}>Входить кодом вместо пароля</Button>}
      {!data.otp && <Button variant="ghost" onClick={reload}>Я подключил — обновить</Button>}
    </Card>
  );
}
