// Каналы уведомлений пользователя (ADR 0031): с какого уровня слать на почту и в Telegram, привязка Telegram.
import { useCallback, useEffect, useState } from 'react';
import { ApiError, api } from '../api/http';
import { useStore } from '../api/store';
import { Badge, Button, Card, Field } from '../components/ui';
import { fmtDateTime } from '../lib';

export type Threshold = 'off' | 'info' | 'warning' | 'critical';

export interface ChannelSettingsView {
  email: string;
  emailLevel: Threshold;
  emailAvailable: boolean;
  telegramLevel: Threshold;
  telegramLinked: boolean;
  telegramLinkedAt?: string;
  telegramAvailable: boolean;
}

export const THRESHOLD_LABEL: Record<Threshold, string> = {
  off: 'Не присылать',
  critical: 'Только важные (остановка агента, сбои)',
  warning: 'Важные и предупреждения',
  info: 'Все уведомления',
};

const ORDER: Threshold[] = ['critical', 'warning', 'info', 'off'];

export function ChannelSettings() {
  const { toast } = useStore();
  const [s, setS] = useState<ChannelSettingsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const fail = useCallback((e: unknown) => toast(e instanceof ApiError ? e.message : 'Нет связи с сервером', 'critical'), [toast]);
  const load = useCallback(async () => {
    try {
      setS(await api<ChannelSettingsView>('GET', '/api/v1/notifications/settings'));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? 'Ваша почта не найдена среди пользователей CostGuard этой компании.' : 'Не удалось загрузить настройки.');
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  // Пока ссылка открыта, ждём, что бот привяжет чат
  useEffect(() => {
    if (!link || s?.telegramLinked) return;
    const t = setInterval(() => { void load(); }, 3000);
    return () => clearInterval(t);
  }, [link, s?.telegramLinked, load]);
  useEffect(() => { if (s?.telegramLinked) setLink(null); }, [s?.telegramLinked]);

  const save = async (patch: Partial<Pick<ChannelSettingsView, 'emailLevel' | 'telegramLevel'>>) => {
    if (!s) return;
    setBusy(true);
    try {
      setS(await api<ChannelSettingsView>('PUT', '/api/v1/notifications/settings', { emailLevel: s.emailLevel, telegramLevel: s.telegramLevel, ...patch }));
      toast('Настройки уведомлений сохранены', 'good');
    } catch (e) { fail(e); } finally { setBusy(false); }
  };

  const connect = async () => {
    setBusy(true);
    try {
      const r = await api<{ url: string }>('POST', '/api/v1/notifications/telegram-link');
      setLink(r.url);
      window.open(r.url, '_blank', 'noopener');
    } catch (e) { fail(e); } finally { setBusy(false); }
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      setS(await api<ChannelSettingsView>('DELETE', '/api/v1/notifications/telegram'));
      toast('Telegram отключён', 'info');
    } catch (e) { fail(e); } finally { setBusy(false); }
  };

  if (error) return <Card title="Каналы уведомлений"><p className="muted">{error}</p></Card>;
  if (!s) return <Card title="Каналы уведомлений"><p className="muted">Загружаем…</p></Card>;

  return (
    <Card title="Каналы уведомлений">
      <p className="muted small">Лента на этой странице общая для компании. На почту и в Telegram приходит то, что касается вас: риск и остановка ваших агентов, согласования, находки — по вашей роли.</p>
      <div className="grid-2">
        <Field label={`Почта · ${s.email}`} hint={s.emailAvailable ? undefined : 'Почта не настроена на сервере (SPRING_MAIL_HOST, MAIL_FROM)'}>
          <select className="input" aria-label="Уровень для почты" value={s.emailLevel} disabled={busy || !s.emailAvailable} onChange={(e) => void save({ emailLevel: e.target.value as Threshold })}>
            {ORDER.map((t) => <option key={t} value={t}>{THRESHOLD_LABEL[t]}</option>)}
          </select>
        </Field>
        <Field label="Telegram" hint={s.telegramAvailable ? undefined : 'Бот не настроен на сервере (TELEGRAM_BOT_TOKEN)'}>
          <select className="input" aria-label="Уровень для Telegram" value={s.telegramLevel} disabled={busy || !s.telegramAvailable} onChange={(e) => void save({ telegramLevel: e.target.value as Threshold })}>
            {ORDER.map((t) => <option key={t} value={t}>{THRESHOLD_LABEL[t]}</option>)}
          </select>
        </Field>
      </div>
      {s.telegramAvailable && (
        <div className="row-gap wrap">
          {s.telegramLinked ? <>
            <Badge tone="good">Telegram подключён{s.telegramLinkedAt ? ` · ${fmtDateTime(s.telegramLinkedAt)}` : ''}</Badge>
            <Button variant="ghost" disabled={busy} onClick={() => void disconnect()}>Отключить Telegram</Button>
          </> : <>
            <Badge tone="neutral">Telegram не подключён</Badge>
            <Button variant="primary" disabled={busy} onClick={() => void connect()}>Подключить Telegram</Button>
          </>}
        </div>
      )}
      {link && !s.telegramLinked && (
        <p className="small" role="status">Откройте бота и нажмите «Start»: <a href={link} target="_blank" rel="noopener noreferrer">{link.replace(/\?start=.*/, '')}</a>. Ссылка одноразовая и действует 15 минут; страница обновится сама.</p>
      )}
    </Card>
  );
}
