// Запрос на покупку ПО (ADR 0056): система сверяет с лицензиями компании; есть лицензия — покупка не нужна,
// иначе решает финансист. Сотрудник и руководитель отдела получают «Одобрено» или «Отклонено».
import { useState } from 'react';
import { api, download, postForm } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty, Field } from '../../components/ui';
import { fmtDate } from '../../lib';
import { errorText, useApi } from './useApi';

interface PurchaseRequest {
  id: string; employeeName: string; product: string; vendor: string | null; amount: number; currency: string;
  period: 'once' | 'month' | 'year'; reason: string | null; fileName: string | null;
  status: 'pending' | 'approved' | 'rejected' | 'has_license' | 'cancelled'; matchedProduct: string | null;
  licenseStatus: string | null; decidedBy: string | null; decisionComment: string | null; createdAt: string;
}

const PERIOD: Record<PurchaseRequest['period'], string> = { once: 'разово', month: 'в месяц', year: 'в год' };
const STATUS: Record<PurchaseRequest['status'], { label: string; tone: 'good' | 'warning' | 'critical' | 'neutral' | 'info' }> = {
  pending: { label: 'Ждёт решения', tone: 'warning' },
  approved: { label: 'Одобрено', tone: 'good' },
  rejected: { label: 'Отклонено', tone: 'critical' },
  has_license: { label: 'Отклонено: есть лицензия', tone: 'info' },
  cancelled: { label: 'Отменён', tone: 'neutral' },
};

const money = (n: number, currency: string) => new Intl.NumberFormat('ru-RU', { style: 'currency', currency, maximumFractionDigits: 2 }).format(n);

function Row({ r, who, actions, file }: { r: PurchaseRequest; who?: boolean; actions?: React.ReactNode; file: () => void }) {
  return (
    <tr>
      {who && <td>{r.employeeName}</td>}
      <td>{r.product}{r.vendor && <span className="muted small block">{r.vendor}</span>}{r.reason && <span className="muted small block">{r.reason}</span>}</td>
      <td className="num">{money(r.amount, r.currency)}<span className="muted small block">{PERIOD[r.period]}</span></td>
      <td><Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge>{r.decisionComment && <span className="muted small block">{r.decisionComment}</span>}</td>
      <td>{fmtDate(r.createdAt)}</td>
      <td className="row-gap">{r.fileName && <Button variant="ghost" icon="download" onClick={file}>Счёт</Button>}{actions}</td>
    </tr>
  );
}

export function MyPurchases() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<PurchaseRequest[]>('/api/v1/me/purchase-requests');
  const [product, setProduct] = useState('');
  const [vendor, setVendor] = useState('');
  const [amount, setAmount] = useState('');
  const [period, setPeriod] = useState<PurchaseRequest['period']>('month');
  const [reason, setReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  if (error) return <Empty>{error}</Empty>;
  const send = async () => {
    setBusy(true);
    try {
      const r = await postForm<PurchaseRequest>('/api/v1/me/purchase-requests', { product, vendor, amount: amount.replace(',', '.'), period, reason }, file);
      toast(r.status === 'has_license' ? `Покупка не нужна: ${r.decisionComment}` : 'Запрос отправлен финансисту', r.status === 'has_license' ? 'info' : 'good');
      setProduct(''); setVendor(''); setAmount(''); setReason(''); setFile(null);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    } finally {
      setBusy(false);
    }
  };
  const cancel = async (id: string) => {
    try { await api('DELETE', `/api/v1/me/purchase-requests/${id}`); reload(); } catch (e) { toast(errorText(e), 'critical'); }
  };
  return (
    <>
      <Card title="Запрос на покупку">
        <p className="muted small">Приложите счёт или чек. Если такая программа у компании уже есть, лицензию выдадут из общего пула, а покупать не придётся.</p>
        <div className="form-row">
          <Field label="Программа, подписка или API"><input className="input" value={product} onChange={(e) => setProduct(e.target.value)} placeholder="Figma Professional" /></Field>
          <Field label="Поставщик"><input className="input" value={vendor} onChange={(e) => setVendor(e.target.value)} /></Field>
          <Field label="Сумма"><input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Оплата"><select className="input" value={period} onChange={(e) => setPeriod(e.target.value as PurchaseRequest['period'])}>{Object.entries(PERIOD).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></Field>
        </div>
        <div className="form-row">
          <Field label="Зачем"><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <Field label="Счёт или чек"><input className="input" type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
        </div>
        <Button variant="primary" disabled={!product.trim() || !amount || busy} onClick={() => { void send(); }}>Отправить</Button>
      </Card>
      <Card title="Мои запросы">
        {!data ? <Empty>Загрузка…</Empty> : data.length === 0 ? <Empty>Запросов нет</Empty> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Что</th><th className="num">Сумма</th><th>Статус</th><th>Дата</th><th /></tr></thead>
            <tbody>{data.map((r) => <Row key={r.id} r={r} file={() => { download(`/api/v1/me/purchase-requests/${r.id}/file`, r.fileName ?? 'счёт').catch((e) => toast(errorText(e), 'critical')); }}
              actions={r.status === 'pending' && <ConfirmButton variant="ghost" title="Отменить запрос?" text={r.product} confirmLabel="Отменить" onConfirm={() => { void cancel(r.id); }}>Отменить</ConfirmButton>} />)}</tbody>
          </table></div>
        )}
      </Card>
    </>
  );
}

/** owner и finance решают; it_admin видит. */
export function EmployerPurchases({ canDecide }: { canDecide: boolean }) {
  const { toast } = useStore();
  const { data, error, reload } = useApi<PurchaseRequest[]>('/api/v1/purchase-requests');
  const decide = async (r: PurchaseRequest, approve: boolean) => {
    const comment = window.prompt(approve ? 'Комментарий (необязательно)' : 'Причина отказа (необязательно)') ?? '';
    try { await api('PUT', `/api/v1/purchase-requests/${r.id}/decision`, { approve, comment }); reload(); } catch (e) { toast(errorText(e), 'critical'); }
  };
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  return (
    <Card title="Запросы на покупку ПО">
      {data.length === 0 ? <Empty>Запросов нет</Empty> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Сотрудник</th><th>Что</th><th className="num">Сумма</th><th>Статус</th><th>Дата</th><th /></tr></thead>
          <tbody>{data.map((r) => <Row key={r.id} who r={r} file={() => { download(`/api/v1/purchase-requests/${r.id}/file`, r.fileName ?? 'счёт').catch((e) => toast(errorText(e), 'critical')); }}
            actions={canDecide && r.status === 'pending' && <><Button variant="primary" onClick={() => { void decide(r, true); }}>Одобрить</Button><Button variant="ghost" onClick={() => { void decide(r, false); }}>Отклонить</Button></>} />)}</tbody>
        </table></div>
      )}
    </Card>
  );
}
