// Подключённые системы (ADR 0019, docs/architecture/connections-page.md).
// В ядре это модуль connections: GET/POST /api/v1/connections, GET /api/v1/connections/types,
// GET /api/v1/connections/{id}, GET /api/v1/connections/{id}/runs, POST /api/v1/connections/{id}/sync,
// DELETE /api/v1/connections/{id}. Менять могут owner и it_admin, смотреть — остальные роли раздела.
import { Fragment, useCallback, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Connection, ConnectionStatus, SystemType } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Empty, Field, Modal, PageHeader, ReadOnlyNote, type Tone } from '../components/ui';
import { fmtDateTime, fmtNum, fmtRelative } from '../lib';

export const CONNECTION_STATUS: Record<ConnectionStatus, { label: string; tone: Tone }> = {
  connected: { label: 'Работает', tone: 'good' },
  failing: { label: 'Ошибка', tone: 'critical' },
  pending: { label: 'Ждёт первой выгрузки', tone: 'info' },
  sync_requested: { label: 'Синхронизация запрошена', tone: 'warning' },
  disconnected: { label: 'Отключена', tone: 'neutral' },
};

export const KIND_LABEL: Record<string, string> = {
  accounting: 'Учёт', hr: 'Кадры', identity: 'Вход в сервисы', bank: 'Банк', devices: 'Техника', cloud: 'Облако', mail: 'Почта',
};

const SETTING_LABEL: Record<string, string> = {
  url: 'Адрес', user: 'Пользователь', org_id: 'ID организации', directory_id: 'ID каталога (tenant)', client_id: 'ID приложения',
  admin: 'Администратор', customer: 'ID клиента Google', host: 'IMAP-сервер', folder: 'Папка',
  accounts: 'Счета (через запятую)', ignore: 'Исключить (через запятую)', min_image_kb: 'Мин. размер картинки, КБ', min_image_side: 'Мин. сторона картинки, px',
};
const NUMERIC = new Set(['min_image_kb', 'min_image_side']);

function useTypeOf() {
  const { systemTypes } = useStore();
  return useCallback((type: string): SystemType | undefined => systemTypes.find((t) => t.type === type), [systemTypes]);
}
const firstLine = (text: string) => text.split('\n')[0];

export function ConnectionStatusBadge({ status }: { status: ConnectionStatus }) {
  const m = CONNECTION_STATUS[status];
  return <Badge tone={m.tone}>{m.label}</Badge>;
}

function LastSync({ c }: { c: Connection }) {
  if (!c.lastSyncAt) return <span className="muted">ещё не было</span>;
  return <span title={fmtDateTime(c.lastSyncAt)}>{fmtRelative(c.lastSyncAt)}</span>;
}

function Records({ c }: { c: Connection }) {
  if (c.recordsLastSync == null) return <span className="muted">—</span>;
  return <>{fmtNum(c.recordsLastSync)} за последнюю<span className="muted small block">{fmtNum(c.recordsTotal)} всего</span></>;
}

function LastError({ c }: { c: Connection }) {
  if (!c.lastError || !c.lastErrorAt) return <span className="muted">—</span>;
  // Ошибка в прошлом у работающей системы — приглушённо
  const old = c.status === 'connected' && !!c.lastSyncAt && c.lastErrorAt < c.lastSyncAt;
  return (
    <span className={old ? 'muted' : ''} title={c.lastError}>
      {firstLine(c.lastError)}<span className="muted small block">{fmtRelative(c.lastErrorAt)}</span>
    </span>
  );
}

function Actions({ c, onReconnect }: { c: Connection; onReconnect: (c: Connection) => void }) {
  const { actions } = useStore();
  if (c.status === 'disconnected') {
    return <Button variant="ghost" onClick={() => onReconnect(c)}>Подключить снова</Button>;
  }
  return (
    <span className="row-gap">
      <Button variant="ghost" disabled={c.status === 'sync_requested'} onClick={() => actions.syncSystem(c.id)}>Синхронизировать</Button>
      <ConfirmButton variant="ghost" title={`Отключить «${c.name}»?`} text="Агент перестанет выгружать систему, история останется." confirmLabel="Отключить" onConfirm={() => actions.disconnectSystem(c.id)}>Отключить</ConfirmButton>
    </span>
  );
}

export function Connections() {
  const { state } = useStore();
  const typeOf = useTypeOf();
  const { canEdit } = useAuth();
  const navigate = useNavigate();
  const edit = canEdit('connections');
  const [kind, setKind] = useState('all');
  const [form, setForm] = useState<{ open: boolean; again?: Connection }>({ open: false });

  const kinds = useMemo(() => [...new Set(state.connections.map((c) => typeOf(c.type)?.kind ?? 'other'))], [state.connections, typeOf]);
  const rows = state.connections.filter((c) => kind === 'all' || typeOf(c.type)?.kind === kind);
  const connectButton = edit ? <Button variant="primary" icon="plus" onClick={() => setForm({ open: true })}>Подключить систему</Button> : undefined;

  return (
    <>
      <PageHeader title="Подключённые системы" subtitle="Откуда агент коннекторов забирает данные: учёт, кадры, вход в сервисы, банк, техника. Секреты задаются в окружении агента, не здесь"
        actions={state.connections.length > 0 && connectButton} />
      {!edit && <ReadOnlyNote />}

      {state.connections.length === 0 ? (
        <Card>
          <Empty action={connectButton}>
            <strong className="block">Пока ничего не подключено</strong>
            <span className="muted">
              Начать можно без подключений: перешлите счета на почтовый ящик CostGuard или загрузите Excel в разделе{' '}
              <Link to="/imports">«Импорт данных»</Link>. Подключение систем добавит выгрузку по расписанию.
            </span>
          </Empty>
        </Card>
      ) : (
        <>
          <div className="chips" role="group" aria-label="Тип системы">
            <button className={`chip ${kind === 'all' ? 'active' : ''}`} onClick={() => setKind('all')}>Все · {state.connections.length}</button>
            {kinds.map((k) => (
              <button key={k} className={`chip ${kind === k ? 'active' : ''}`} onClick={() => setKind(k)}>
                {KIND_LABEL[k] ?? k} · {state.connections.filter((c) => typeOf(c.type)?.kind === k).length}
              </button>
            ))}
          </div>
          <Card>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Система</th><th>Тип</th><th>Статус</th><th>Последняя синхронизация</th><th className="num">Записей</th><th>Последняя ошибка</th>{edit && <th />}</tr></thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id} data-testid={`connection-${c.type}`}>
                      <td>
                        <Link to={`/connections/${c.id}`}>{c.name}</Link>
                        <span className="muted small block">{typeOf(c.type)?.title ?? c.type}</span>
                        {!c.managedInCore && <span className="muted small block" title="Настройки в ядре неизвестны; подключение отсюда заберёт управление">Настроено в файле агента</span>}
                      </td>
                      <td>{KIND_LABEL[typeOf(c.type)?.kind ?? ''] ?? '—'}</td>
                      <td><ConnectionStatusBadge status={c.status} /></td>
                      <td><LastSync c={c} /></td>
                      <td className="num"><Records c={c} /></td>
                      <td><LastError c={c} /></td>
                      {edit && <td><Actions c={c} onReconnect={(again) => setForm({ open: true, again })} /></td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}
      {form.open && <ConnectForm again={form.again} onClose={() => setForm({ open: false })} onDone={(id) => navigate(`/connections/${id}`)} />}
    </>
  );
}

export function ConnectionDetail() {
  const { id } = useParams();
  const { state } = useStore();
  const typeOf = useTypeOf();
  const { canEdit } = useAuth();
  const [again, setAgain] = useState<Connection | null>(null);
  const navigate = useNavigate();
  const c = state.connections.find((x) => x.id === id);
  if (!c) {
    return <Empty action={<Link to="/connections" className="btn btn-primary">К списку систем</Link>}>Система не найдена</Empty>;
  }
  const t = typeOf(c.type);
  const edit = canEdit('connections');
  const runs = state.connectionRuns.filter((r) => r.connectionId === c.id).slice(0, 20);
  const needsSecrets = !!c.lastError?.startsWith('Missing setting or secret');
  const settings = Object.entries(c.settings);

  return (
    <>
      <PageHeader title={c.name} subtitle={t?.title} back={{ to: '/connections', label: 'Подключённые системы' }}
        actions={edit && <Actions c={c} onReconnect={setAgain} />} />
      {!edit && <ReadOnlyNote />}
      <div className="grid-2">
        <Card title="Состояние">
          <dl className="dl">
            <dt>Статус</dt><dd><ConnectionStatusBadge status={c.status} /></dd>
            <dt>Тип</dt><dd>{KIND_LABEL[t?.kind ?? ''] ?? '—'}</dd>
            <dt>Последняя синхронизация</dt><dd><LastSync c={c} /></dd>
            <dt>Записей</dt><dd><Records c={c} /></dd>
            <dt>Подключена</dt><dd>{fmtDateTime(c.createdAt)}</dd>
            {c.syncRequestedAt && <><dt>Синхронизация запрошена</dt><dd>{fmtDateTime(c.syncRequestedAt)}</dd></>}
            <dt>Управление</dt><dd>{c.managedInCore ? 'Настройки в CostGuard' : 'Настроено в файле агента'}</dd>
          </dl>
          {c.lastError && <>
            <h3 className="h3">Последняя ошибка {c.lastErrorAt && <span className="muted small">· {fmtDateTime(c.lastErrorAt)}</span>}</h3>
            <pre className="code-block">{c.lastError}</pre>
          </>}
        </Card>
        <Card title="Настройки">
          {settings.length === 0 ? <p className="muted">Настроек без секретов нет{c.managedInCore ? '' : ': система настроена в файле агента'}</p> : (
            <dl className="dl">{settings.map(([k, v]) => <Fragment key={k}><dt>{SETTING_LABEL[k] ?? k}</dt><dd>{v}</dd></Fragment>)}</dl>
          )}
          {t && t.secrets.length > 0 && (
            <div className={`callout ${needsSecrets ? 'callout-critical' : ''}`} role={needsSecrets ? 'alert' : undefined}>
              <strong className="block">Что нужно агенту</strong>
              <span className="muted small block">Секреты задают при подключении (хранятся в CostGuard зашифрованными) или в окружении агента коннекторов.</span>
              <ul className="plain">{t.secrets.map((s) => <li key={s}><code>{s}</code></li>)}</ul>
            </div>
          )}
        </Card>
      </div>
      <Card title="Последние выгрузки">
        {runs.length === 0 ? <Empty>Выгрузок ещё не было</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Начало</th><th className="num">Длительность</th><th>Результат</th><th className="num">Записей</th><th>Ошибка</th></tr></thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id}>
                    <td>{fmtDateTime(r.startedAt)}</td>
                    <td className="num">{r.finishedAt ? `${Math.round((new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime()) / 1000)} с` : '—'}</td>
                    <td>{r.status === 'SUCCEEDED' ? <Badge tone="good">Успешно</Badge> : <Badge tone="critical">Ошибка</Badge>}</td>
                    <td className="num">{fmtNum(r.records)}</td>
                    <td>{r.error ? <pre className="code-block">{r.error}</pre> : <span className="muted">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {again && <ConnectForm again={again} onClose={() => setAgain(null)} onDone={(newId) => navigate(`/connections/${newId}`)} />}
    </>
  );
}

function ConnectForm({ again, onClose, onDone }: { again?: Connection; onClose: () => void; onDone: (id: string) => void }) {
  const { state, actions, mode, systemTypes } = useStore();
  const typeOf = useTypeOf();
  const [type, setType] = useState<string | null>(again?.type ?? null);
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const t = type ? typeOf(type) : undefined;
  const [name, setName] = useState(again?.name ?? '');
  const [settings, setSettings] = useState<Record<string, string>>(again?.settings ?? {});
  const [tried, setTried] = useState(false);

  const taken = (st: SystemType) => state.connections.some((c) => c.type === st.type && c.enabled && c.managedInCore);
  const groups = [...new Set(systemTypes.map((s) => s.kind))];
  const missing = t ? t.required.filter((k) => !settings[k]?.trim()) : [];

  const choose = (st: SystemType) => { setType(st.type); setName(st.title); setSettings({}); setTried(false); };
  const submit = async () => {
    setTried(true);
    if (!t || missing.length || !name.trim()) return;
    const id = await actions.connectSystem(t.type, name, settings, secrets);
    if (id) { onClose(); onDone(id); }
  };

  return (
    <Modal open wide onClose={onClose} title={again ? `Подключить снова: ${again.name}` : 'Подключить систему'} footer={
      <>
        {t && !again && <Button variant="ghost" onClick={() => setType(null)}>Другая система</Button>}
        <Button variant="ghost" onClick={onClose}>Отмена</Button>
        {t && <Button variant="primary" onClick={submit}>Подключить</Button>}
      </>
    }>
      {!t ? (
        <div className="form">
          {groups.map((g) => (
            <div key={g}>
              <h3 className="h3">{KIND_LABEL[g] ?? g}</h3>
              <div className="type-grid">
                {systemTypes.filter((s) => s.kind === g).map((s) => (
                  <button key={s.type} type="button" className="type-card" disabled={taken(s)} onClick={() => choose(s)}>
                    <strong>{s.title}</strong>
                    {taken(s) && <span className="muted small block">Уже подключено</span>}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="form">
          <Field label="Название" hint={tried && !name.trim() ? 'Укажите название' : undefined}>
            <input className="input" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
          </Field>
          {[...t.required.map((k) => [k, true] as const), ...t.optional.map((k) => [k, false] as const)].map(([k, req]) => {
            const bad = tried && req && !settings[k]?.trim();
            return (
              <Field key={k} label={`${SETTING_LABEL[k] ?? k}${req ? '' : ' (необязательно)'}`} hint={bad ? 'Обязательное поле' : undefined}>
                <input className={`input ${bad ? 'input-error' : ''}`} type={NUMERIC.has(k) ? 'number' : 'text'} aria-invalid={bad}
                  value={settings[k] ?? ''} onChange={(e) => setSettings({ ...settings, [k]: e.target.value })} />
              </Field>
            );
          })}
          {t.secrets.length > 0 && mode === 'api' && <>
            <h3 className="h3">Секреты</h3>
            <p className="muted small">Хранятся в CostGuard в зашифрованном виде и уходят только агенту коннекторов; обратно не показываются. Оставьте пустыми, если секреты заданы в окружении агента.</p>
            {t.secrets.map((k) => (
              <Field key={k} label={k}>
                <input className="input" type="password" autoComplete="new-password" value={secrets[k] ?? ''} onChange={(e) => setSecrets({ ...secrets, [k]: e.target.value })} />
              </Field>
            ))}
          </>}
          {t.secrets.length > 0 && mode === 'demo' && (
            <div className="callout">
              <strong className="block">Задайте в окружении агента</strong>
              <span className="muted small block">Пароли и токены сюда не вводятся: ядро их не хранит.</span>
              <ul className="plain">{t.secrets.map((s) => <li key={s}><code>{s}</code></li>)}</ul>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
