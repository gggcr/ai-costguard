// Доступ у поставщика (ADR 0065): как место подписки выдаётся и отзывается в самой программе — через Microsoft Entra
// (назначение корпоративного приложения, поставщику пользователя создаёт или удаляет Entra) или по SCIM 2.0.
import { useState } from 'react';
import { api } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Empty, Field, Modal } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface Access {
  kind: 'entra' | 'scim' | 'manual'; entraAppId: string | null; entraAppName: string | null; scimUrl: string | null;
  scimTokenSet: boolean; scimRevoke: 'deactivate' | 'delete';
  recent: { id: string; employeeName: string | null; action: 'grant' | 'revoke'; status: 'done' | 'failed'; detail: string | null; at: string }[];
}

export function AccessModal({ subscriptionId, product, onClose }: { subscriptionId: string; product: string; onClose: () => void }) {
  const { toast } = useStore();
  const { data, error, reload } = useApi<Access>(`/api/v1/license-pool/${subscriptionId}/access`);
  const [kind, setKind] = useState<Access['kind'] | null>(null);
  const [app, setApp] = useState<string | null>(null);
  const [scimUrl, setScimUrl] = useState<string | null>(null);
  const [token, setToken] = useState('');
  const [revoke, setRevoke] = useState<Access['scimRevoke'] | null>(null);
  const k = kind ?? data?.kind ?? 'manual';
  const apps = useApi<{ id: string; name: string }[]>(k === 'entra' ? '/api/v1/microsoft/apps' : null);
  if (error) return <Modal open onClose={onClose} title={product}><Empty>{error}</Empty></Modal>;
  if (!data) return <Modal open onClose={onClose} title={product}><Empty>Загрузка…</Empty></Modal>;
  const appId = app ?? data.entraAppId ?? '';
  const url = scimUrl ?? data.scimUrl ?? '';
  const rv = revoke ?? data.scimRevoke;

  const save = async () => {
    try {
      await api('PUT', `/api/v1/license-pool/${subscriptionId}/access`, {
        kind: k, entraAppId: k === 'entra' ? appId : null,
        entraAppName: k === 'entra' ? apps.data?.find((a) => a.id === appId)?.name ?? data.entraAppName : null,
        scimUrl: k === 'scim' ? url.trim() : null, scimToken: k === 'scim' && token.trim() ? token.trim() : null, scimRevoke: rv,
      });
      toast('Сохранено', 'good');
      setToken('');
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };

  return (
    <Modal open onClose={onClose} title={`Доступ у поставщика: ${product}`} footer={<><Button variant="ghost" onClick={onClose}>Закрыть</Button><Button variant="primary" onClick={() => { void save(); }}>Сохранить</Button></>}>
      <div className="form">
        <p className="muted small">Когда лицензия выдаётся из пула или возвращается в пул, CostGuard сам даёт или забирает доступ в программе.</p>
        <Field label="Как выдавать и отзывать доступ">
          <select className="input" value={k} onChange={(e) => setKind(e.target.value as Access['kind'])}>
            <option value="manual">Вручную (ИТ по чек-листу)</option>
            <option value="entra">Через Microsoft Entra (корпоративное приложение)</option>
            <option value="scim">Напрямую по SCIM 2.0 (Figma, Slack, Zoom, Miro, Notion…)</option>
          </select>
        </Field>
        {k === 'entra' && (
          <Field label="Приложение в Microsoft Entra" hint="Нужны разрешения Application.Read.All и AppRoleAssignment.ReadWrite.All; в самом приложении включена автоматическая подготовка пользователей">
            {apps.error ? <Empty>{apps.error}</Empty> : (
              <select className="input" value={appId} onChange={(e) => setApp(e.target.value)}>
                <option value="">{apps.data ? 'Выберите' : 'Загрузка…'}</option>
                {apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            )}
          </Field>
        )}
        {k === 'scim' && <>
          <Field label="Адрес SCIM (https://…/scim/v2)"><input className="input" value={url} onChange={(e) => setScimUrl(e.target.value)} placeholder="https://www.figma.com/scim/v2/..." /></Field>
          <Field label={data.scimTokenSet ? 'Токен (пусто — оставить прежний)' : 'Токен'}><input className="input" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} /></Field>
          <Field label="При возврате в пул"><select className="input" value={rv} onChange={(e) => setRevoke(e.target.value as Access['scimRevoke'])}><option value="deactivate">Деактивировать пользователя</option><option value="delete">Удалить пользователя</option></select></Field>
        </>}
        <h4>Последние действия</h4>
        {data.recent.length === 0 ? <Empty>Пока ничего</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Сотрудник</th><th>Действие</th><th>Итог</th><th>Когда</th></tr></thead>
            <tbody>{data.recent.map((r) => (
              <tr key={r.id}><td>{r.employeeName ?? '—'}</td><td>{r.action === 'grant' ? 'выдать' : 'отозвать'}</td>
                <td>{r.status === 'done' ? <Badge tone="good">готово</Badge> : <Badge tone="critical">ошибка</Badge>}<span className="muted small block">{r.detail}</span></td><td>{fmtDate(r.at)}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </div>
    </Modal>
  );
}
