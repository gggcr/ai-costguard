// Вход сотрудников через Microsoft 365 (ADR 0058): владелец один раз подключает Entra ID компании.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty, Field } from '../../components/ui';
import { errorText, useApi } from '../cabinet/useApi';

interface CompanyLogin { connected: boolean; directoryId: string | null; clientId: string | null; domains: string[]; redirectUri: string }

export function MicrosoftLoginCard({ owner }: { owner: boolean }) {
  const { toast } = useStore();
  const { data, error, reload } = useApi<CompanyLogin>('/api/v1/company/login');
  const [form, setForm] = useState<{ directoryId: string; clientId: string; clientSecret: string; domains: string } | null>(null);
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const f = form ?? { directoryId: data.directoryId ?? '', clientId: data.clientId ?? '', clientSecret: '', domains: data.domains.join(', ') };
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...f, [k]: e.target.value });
  const save = async () => {
    try {
      await api('PUT', '/api/v1/company/login', {
        directoryId: f.directoryId.trim(), clientId: f.clientId.trim(), clientSecret: f.clientSecret.trim() || null,
        domains: f.domains.split(/[\s,;]+/).filter(Boolean),
      });
      toast('Вход через Microsoft 365 подключён', 'good');
      setForm(null);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  const remove = async () => {
    try { await api('DELETE', '/api/v1/company/login'); setForm(null); reload(); } catch (e) { toast(errorText(e), 'critical'); }
  };
  return (
    <Card title="Вход сотрудников через Microsoft 365">
      <p className="muted small">Сотрудники входят рабочей учётной записью Microsoft, а затем вводят код из Google Authenticator (настраивается при первом входе). Входят только те, у кого уже есть учётная запись CostGuard: при первом входе её владелец подтверждает связь по почте или паролем. Вход по паролю остаётся запасным.</p>
      <p>{data.connected ? <Badge tone="good">Подключён</Badge> : <Badge>Не подключён</Badge>}</p>
      <p className="small">Адрес перенаправления для регистрации приложения в Microsoft Entra: <code>{data.redirectUri}</code></p>
      {owner && <>
        <div className="form-row">
          <Field label="ID каталога (tenant ID)"><input className="input" value={f.directoryId} onChange={set('directoryId')} placeholder="xxxxxxxx-xxxx-…" /></Field>
          <Field label="ID приложения (client ID)"><input className="input" value={f.clientId} onChange={set('clientId')} /></Field>
          <Field label={data.connected ? 'Секрет клиента (пусто — оставить прежний)' : 'Секрет клиента'}><input className="input" type="password" autoComplete="off" value={f.clientSecret} onChange={set('clientSecret')} /></Field>
          <Field label="Домены почты сотрудников"><input className="input" value={f.domains} onChange={set('domains')} placeholder="company.ru, company.com" /></Field>
        </div>
        <Button variant="primary" disabled={!f.directoryId.trim() || !f.clientId.trim() || !f.domains.trim() || (!data.connected && !f.clientSecret.trim())} onClick={() => { void save(); }}>{data.connected ? 'Сохранить' : 'Подключить'}</Button>
        {data.connected && <ConfirmButton variant="ghost" title="Отключить вход через Microsoft 365?" text="Сотрудники будут входить по паролю" confirmLabel="Отключить" onConfirm={() => { void remove(); }}>Отключить</ConfirmButton>}
      </>}
    </Card>
  );
}
