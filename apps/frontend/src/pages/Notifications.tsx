import { Link, useNavigate } from 'react-router-dom';
import { useStore } from '../api/store';
import { Button, Card, Empty, PageHeader } from '../components/ui';
import { fmtDateTime } from '../lib';

const LEVEL: Record<string, string> = { info: 'Информация', warning: 'Внимание', critical: 'Важно', good: 'Готово' };

export function Notifications() {
  const { state, actions } = useStore();
  const navigate = useNavigate();
  const unread = state.notifications.filter((n) => !n.read).length;
  return (
    <>
      <PageHeader title="Уведомления" subtitle="В рабочей версии приходят в реальном времени через SSE, а также в Telegram и на почту"
        actions={<><Button disabled={!unread} onClick={() => actions.markAllRead()}>Прочитать все</Button><Button variant="ghost" disabled={!state.notifications.length} onClick={() => actions.clearNotifications()}>Очистить</Button></>} />
      <Card>
        {state.notifications.length === 0 ? <Empty>Уведомлений нет</Empty> : (
          <ul className="list">
            {state.notifications.map((n) => (
              <li key={n.id}>
                <button className={`list-row notif-row ${n.read ? '' : 'unread'}`} onClick={() => { actions.markRead(n.id); if (n.link) navigate(n.link); }}>
                  <span className={`dot dot-${n.level}`} aria-hidden="true" />
                  <span className="grow">{n.text}<span className="muted small block">{LEVEL[n.level]} · {fmtDateTime(n.at)}</span></span>
                  {!n.read && <span className="muted small">новое</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

export function NotFound() {
  return <Empty action={<Link to="/" className="btn btn-primary">На главную</Link>}>Страница не найдена</Empty>;
}
