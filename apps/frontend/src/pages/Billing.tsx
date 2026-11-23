// Тариф CostGuard и оплата (ADR 0037, 0038, 0077): текущий тариф и срок, каталог с ценами в валюте компании,
// оформление подписки через страницу провайдера, портал для смены тарифа, карты и отмены, история платежей.
// Статус оплаты меняет только ядро по проверенному уведомлению провайдера: страница ничего не решает сама.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError, api } from '../api/http';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { Badge, Button, Card, PageHeader } from '../components/ui';
import { fmtDate, fmtMoney, fmtNum } from '../lib';

interface Limits { agents?: number | null; employees?: number | null; connectedSystems?: number | null; operationsPerMonth?: number | null }
interface Offer { name: string; title: string; monthlyPrice?: number | null; currency: string; limits: Limits; prices?: Record<string, number> }
interface Subscription { provider: string; status: string; cancelAtPeriodEnd: boolean; paidUntil?: string | null }
interface Current { plan: string; title: string; status: 'trial' | 'active' | 'read_only'; trialEndsAt?: string | null; accessUntil?: string | null; limits: Limits; subscription?: Subscription | null }
interface Payment { id: string; plan: string; amount: number; currency: string; status: string; createdAt: string; paidAt?: string | null }

const STATUS: Record<Current['status'], [string, 'good' | 'warning' | 'critical']> = {
  trial: ['Пробный период', 'warning'],
  active: ['Активен', 'good'],
  read_only: ['Только чтение', 'critical'],
};

const SUBSCRIPTION: Record<string, string> = {
  active: 'Подписка активна',
  trialing: 'Подписка активна',
  past_due: 'Платёж не прошёл, провайдер повторит списание',
  unpaid: 'Подписка не оплачена',
  canceled: 'Подписка отменена',
  incomplete: 'Оплата не завершена',
  incomplete_expired: 'Оплата не завершена',
  paused: 'Подписка приостановлена',
};

const PAYMENT: Record<string, string> = { pending: 'Ожидает оплаты', succeeded: 'Оплачен', canceled: 'Отменён' };

const LIVE = ['active', 'trialing', 'past_due', 'unpaid', 'incomplete', 'paused'];

const limit = (v?: number | null) => (v == null ? 'без ограничений' : fmtNum(v));

export function Billing() {
  const { toast, company, mode } = useStore();
  const { canEdit } = useAuth();
  const owner = canEdit('billing');
  const [params, setParams] = useSearchParams();
  const [current, setCurrent] = useState<Current | null>(null);
  const [unassigned, setUnassigned] = useState(false);
  const [offers, setOffers] = useState<Offer[]>([]);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [currency, setCurrency] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const fail = useCallback((e: unknown) => toast(e instanceof ApiError ? e.message : 'Нет связи с сервером', 'critical'), [toast]);

  const load = useCallback(async () => {
    try {
      const [plans, list] = await Promise.all([
        api<Offer[]>('GET', '/api/v1/billing/plans'),
        api<Payment[]>('GET', '/api/v1/billing/payments'),
      ]);
      setOffers(plans);
      setPayments(list);
      try {
        setCurrent(await api<Current>('GET', '/api/v1/billing/plan'));
        setUnassigned(false);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) setUnassigned(true); else throw e;
      }
      setError(null);
    } catch {
      setError('Не удалось загрузить тариф.');
    }
  }, []);

  useEffect(() => { if (mode === 'api') void load(); }, [load, mode]);

  // Возврат со страницы оплаты: тариф изменится, когда провайдер пришлёт подтверждение — несколько раз перечитываем
  const returned = params.get('checkout');
  useEffect(() => {
    if (!returned) return;
    toast(returned === 'success' ? 'Оплата принята: тариф обновится, как только провайдер подтвердит платёж' : 'Оплата отменена', returned === 'success' ? 'good' : 'warning');
    setParams({}, { replace: true });
    let n = 0;
    const t = setInterval(() => { n += 1; void load(); if (n >= 10) clearInterval(t); }, 3000);
    return () => clearInterval(t);
  }, [returned, load, toast, setParams]);

  const currencies = useMemo(() => {
    const all = new Set<string>();
    offers.forEach((o) => Object.keys(o.prices ?? {}).forEach((c) => all.add(c)));
    return [...all].sort();
  }, [offers]);
  const shown = currency ?? (currencies.includes(company.currency) ? company.currency : currencies.includes('USD') ? 'USD' : currencies[0] ?? 'USD');

  const subscription = current?.subscription ?? null;
  const live = subscription != null && LIVE.includes(subscription.status);

  const subscribe = async (plan: string) => {
    setBusy(true);
    try {
      const r = await api<{ status: string; confirmationUrl?: string | null }>('POST', '/api/v1/billing/checkout', { plan, currency: shown });
      if (r.confirmationUrl) {
        window.location.assign(r.confirmationUrl);
        return;
      }
      toast('Тариф оплачен', 'good');
      await load();
    } catch (e) { fail(e); } finally { setBusy(false); }
  };

  const portal = async () => {
    setBusy(true);
    try {
      const r = await api<{ url: string }>('POST', '/api/v1/billing/portal');
      window.location.assign(r.url);
    } catch (e) { fail(e); setBusy(false); }
  };

  if (mode !== 'api') return <Card title="Тариф и оплата"><p className="muted">Оплата тарифа работает, когда интерфейс подключён к ядру. В демо-режиме платежей нет.</p></Card>;
  if (error) return <Card title="Тариф и оплата"><p className="muted">{error}</p></Card>;

  return (
    <div className="stack">
      <PageHeader title="Тариф и оплата" subtitle="Тариф CostGuard, подписка и платежи компании" />
      <Card title="Текущий тариф">
        {unassigned && <p className="muted">Компания работает без ограничений тарифа: тариф назначает оператор CostGuard.</p>}
        {current && (
          <>
            <p>
              <strong>{current.title}</strong>{' '}
              <Badge tone={STATUS[current.status][1]}>{STATUS[current.status][0]}</Badge>
            </p>
            {current.accessUntil && <p>{current.status === 'read_only' ? 'Доступ закончился' : 'Доступ до'}: {fmtDate(current.accessUntil)}</p>}
            {subscription && (
              <p>
                {SUBSCRIPTION[subscription.status] ?? subscription.status}
                {subscription.cancelAtPeriodEnd && subscription.paidUntil ? `. Отменена, работает до ${fmtDate(subscription.paidUntil)}` : ''}
              </p>
            )}
            {current.status === 'read_only' && <p className="muted">Изменения недоступны, пока тариф не оплачен. Данные сохранены.</p>}
            {owner && live && <Button variant="primary" onClick={() => void portal()} disabled={busy}>Управлять подпиской</Button>}
            {owner && live && <p className="muted">Смена тарифа, карта, отмена и счета — на странице платёжного провайдера.</p>}
          </>
        )}
      </Card>

      <Card title="Тарифы">
        {currencies.length > 1 && (
          <p>
            Валюта:{' '}
            <select value={shown} onChange={(e) => setCurrency(e.target.value)} aria-label="Валюта">
              {currencies.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </p>
        )}
        <div className="cards-grid">
          {offers.filter((o) => o.name !== 'trial').map((o) => {
            const price = o.prices?.[shown];
            return (
              <div key={o.name} className="card">
                <h3>{o.title}</h3>
                <p><strong>{price != null ? fmtMoney(price, shown) : '—'}</strong> {price != null ? 'в месяц' : 'нет цены в этой валюте'}</p>
                <ul>
                  <li>Агентов: {limit(o.limits.agents)}</li>
                  <li>Сотрудников: {limit(o.limits.employees)}</li>
                  <li>Подключённых систем: {limit(o.limits.connectedSystems)}</li>
                  <li>Операций шлюза в месяц: {limit(o.limits.operationsPerMonth)}</li>
                </ul>
                {owner && !live && price != null && (
                  <Button variant="primary" onClick={() => void subscribe(o.name)} disabled={busy}>Оформить подписку</Button>
                )}
              </div>
            );
          })}
        </div>
        {!owner && <p className="muted">Оплачивает тариф владелец компании.</p>}
      </Card>

      <Card title="Платежи">
        {payments.length === 0 ? <p className="muted">Платежей пока нет.</p> : (
          <table>
            <thead><tr><th>Дата</th><th>Тариф</th><th>Сумма</th><th>Статус</th></tr></thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.id}>
                  <td>{fmtDate(p.paidAt ?? p.createdAt)}</td>
                  <td>{offers.find((o) => o.name === p.plan)?.title ?? p.plan}</td>
                  <td>{fmtMoney(p.amount, p.currency)}</td>
                  <td>{PAYMENT[p.status] ?? p.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
