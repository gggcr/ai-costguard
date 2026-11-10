// Счета и платежи из конвейера приёма и коннекторов (ADR 0033): к какой подписке относится счёт и каким платежом оплачен.
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/http';
import type { Invoice, Payment } from '../api/generated/core';
import { DATA_MODE, useStore } from '../api/store';
import { Badge, Card, Empty } from '../components/ui';
import { fmtDate } from '../lib';

const money = (v: number | undefined, currency: string | undefined) =>
  new Intl.NumberFormat('ru-RU', { style: 'currency', currency: currency || 'RUB', maximumFractionDigits: 2 }).format(v ?? 0);

const KIND: Record<string, string> = { CARD: 'картой', TRANSFER: 'переводом', SBP: 'по СБП', FEE: 'комиссия банка' };

export function InvoicesTab() {
  const { state } = useStore();
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (DATA_MODE === 'demo') return;
    let alive = true;
    Promise.all([api<Invoice[]>('GET', '/api/v1/invoices'), api<Payment[]>('GET', '/api/v1/payments')])
      .then(([i, p]) => { if (alive) { setInvoices(i); setPayments(p); setError(null); } })
      .catch((e: unknown) => { if (alive) setError(e instanceof ApiError ? e.message : 'Нет связи с сервером'); });
    return () => { alive = false; };
  }, []);

  if (DATA_MODE === 'demo') return <Card><Empty>Счета появятся после загрузки. В демо-режиме счетов нет: они приходят из импорта файлов, почты и банков.</Empty></Card>;
  if (error) return <Card><Empty>Не удалось загрузить счета. {error}</Empty></Card>;
  if (invoices === null) return <Card><p className="muted">Загрузка…</p></Card>;

  const vendor = (id?: string) => state.vendors.find((v) => v.id === id)?.name ?? '—';
  const product = (id?: string) => state.subscriptions.find((s) => s.id === id)?.product;

  return (
    <>
      <Card title="Счета">
        <p className="muted small">Новый счёт по подписке сравнивается с прошлым: так подтверждается экономия по находкам в работе.</p>
        {invoices.length === 0 ? <Empty>Счетов пока нет. Загрузите счёт на странице импорта или подключите почту и учётную систему.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Поставщик</th><th>Счёт</th><th>Дата</th><th>Подписка</th><th className="num">Сумма</th><th>Оплата</th><th>Источник</th></tr></thead>
              <tbody>
                {invoices.map((i) => (
                  <tr key={i.id}>
                    <td>{vendor(i.vendorId)}</td>
                    <td>{i.number || '—'}{(i.lines?.length ?? 0) > 0 && <span className="muted small block">{i.lines!.map((l) => l.description).filter(Boolean).join('; ')}</span>}</td>
                    <td>{fmtDate(i.issuedOn)}{!i.dated && <span className="muted small block">дата получения</span>}</td>
                    <td>{i.subscriptionId ? <Link to={`/software/subscriptions/${i.subscriptionId}`}>{product(i.subscriptionId) ?? 'подписка'}</Link> : <span className="muted">не определена</span>}</td>
                    <td className="num">{money(i.total, i.currency)}</td>
                    <td>{i.paymentId ? <Badge tone="good">оплачен</Badge> : <Badge tone="neutral">нет платежа</Badge>}</td>
                    <td className="muted small">{i.source}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title="Платежи">
        {payments.length === 0 ? <Empty>Платежей пока нет. Загрузите выписку или подключите банк.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Получатель</th><th>Дата</th><th>Назначение</th><th>Способ</th><th className="num">Сумма</th><th>Счёт</th></tr></thead>
              <tbody>
                {payments.map((p) => (
                  <tr key={p.id}>
                    <td>{vendor(p.vendorId)}{p.intermediary && <span className="muted small block">через {p.intermediary}</span>}</td>
                    <td>{fmtDate(p.paidOn)}</td>
                    <td className="small">{p.purpose || '—'}</td>
                    <td>{KIND[p.kind ?? ''] ?? (p.kind || '—')}{p.card && <span className="muted small block">{p.card}</span>}</td>
                    <td className="num">{money(p.amount, p.currency)}</td>
                    <td>{p.invoiceId ? (invoices.find((i) => i.id === p.invoiceId)?.number || 'есть') : p.role === 'commission' ? <span className="muted">комиссия агента</span> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
