// «Мои документы» и личные уведомления сотрудника (ADR 0052), а для работодателя — документы сотрудника.
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, download, uploadForm } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, ConfirmButton, Empty, Field, Modal } from '../../components/ui';
import { fmtDate, fmtDateTime } from '../../lib';
import { errorText, useApi } from './useApi';
import { PARTY_LABEL, SignatureState, SignModal, type DocumentSignature } from './Signing';

export interface EmployeeDocument {
  id: string;
  source: 'upload' | 'asset_act';
  kind: string;
  title: string;
  number: string | null;
  signedOn: string | null;
  fileName: string;
  size: number | null;
  createdAt: string;
  returnedOn: string | null;
  signers: string[];
  signatures: DocumentSignature[];
}

export const KIND_LABEL: Record<string, string> = {
  employment_contract: 'Трудовой договор',
  agreement: 'Соглашение',
  order: 'Приказ',
  other: 'Другое',
  asset_act: 'Акт выдачи техники',
};

function DocumentList({ docs, open, onRemove, onSign }: { docs: EmployeeDocument[]; open: (d: EmployeeDocument) => void; onRemove?: (d: EmployeeDocument) => void; onSign?: (d: EmployeeDocument) => void }) {
  if (docs.length === 0) return <Empty>Документов пока нет</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Документ</th><th>Вид</th><th>Номер</th><th>Дата</th><th>Подпись</th><th /></tr></thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.id}>
              <td>{d.title}{d.returnedOn && <span className="muted small block">Техника возвращена {fmtDate(d.returnedOn)}</span>}</td>
              <td><Badge tone={d.source === 'asset_act' ? 'info' : 'neutral'}>{KIND_LABEL[d.kind] ?? d.kind}</Badge></td>
              <td>{d.number ?? '—'}</td>
              <td>{d.signedOn ? fmtDate(d.signedOn) : fmtDate(d.createdAt)}</td>
              <td>{d.signers.length > 0 ? <SignatureState signers={d.signers} signatures={d.signatures} /> : '—'}</td>
              <td className="row-gap">
                {onSign && d.signers.includes('employee') && !d.signatures.some((s) => s.party === 'employee') && <Button variant="primary" onClick={() => onSign(d)}>Подписать</Button>}
                <Button variant="ghost" icon="download" onClick={() => open(d)}>Скачать</Button>
                {onRemove && d.source === 'upload' && d.signatures.length === 0 && (
                  <ConfirmButton variant="ghost" title="Удалить документ?" text={`«${d.title}» пропадёт у сотрудника.`} confirmLabel="Удалить" onConfirm={() => onRemove(d)}>Удалить</ConfirmButton>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function MyDocuments() {
  const { data, error, reload } = useApi<EmployeeDocument[]>('/api/v1/me/documents');
  const { toast } = useStore();
  const [signing, setSigning] = useState<EmployeeDocument | null>(null);
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  return (
    <Card title="Мои документы">
      {signing && <SignModal title={signing.title} path={`/api/v1/me/documents/${signing.id}/signature`} onClose={() => setSigning(null)} onSigned={() => { setSigning(null); reload(); }} />}
      <p className="muted small">Договор, соглашения и приказы, которые добавил работодатель, и акты выдачи техники. О каждом новом документе приходит уведомление. Документы на подпись подписываются кодом из приложения или ПИН-кодом.</p>
      <DocumentList docs={data} onSign={setSigning} open={(d) => { download(`/api/v1/me/documents/${d.id}/file`, d.fileName).catch((e) => toast(errorText(e), 'critical')); }} />
    </Card>
  );
}

interface Page { items: { id: string; at: string; text: string; link: string | null; level: string; read: boolean }[]; unread: number }

export function MyNotifications() {
  const { data, error, reload } = useApi<Page>('/api/v1/me/notifications');
  const navigate = useNavigate();
  if (error) return <Empty>{error}</Empty>;
  if (!data) return <Empty>Загрузка…</Empty>;
  const read = async (id: string, link: string | null) => {
    await api('PUT', `/api/v1/me/notifications/${id}/read`).catch(() => undefined);
    reload();
    if (link) navigate(link);
  };
  return (
    <Card title="Мои уведомления" actions={<Button disabled={!data.unread} onClick={() => { void api('PUT', '/api/v1/me/notifications/read').then(reload); }}>Прочитать все</Button>}>
      {data.items.length === 0 ? <Empty>Уведомлений нет</Empty> : (
        <ul className="list">
          {data.items.map((n) => (
            <li key={n.id}>
              <button className={`list-row notif-row ${n.read ? '' : 'unread'}`} onClick={() => { void read(n.id, n.link); }}>
                <span className={`dot dot-${n.level}`} aria-hidden="true" />
                <span className="grow">{n.text}<span className="muted small block">{fmtDateTime(n.at)}</span></span>
                {!n.read && <span className="muted small">новое</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** Работодатель: документы сотрудника, загрузка и удаление (owner, finance, it_admin). */
export function EmployeeDocumentsModal({ employeeId, name, onClose }: { employeeId: string; name: string; onClose: () => void }) {
  const { data, error, reload } = useApi<EmployeeDocument[]>(`/api/v1/employees/${employeeId}/documents`);
  const { toast } = useStore();
  const [file, setFile] = useState<File | null>(null);
  const [kind, setKind] = useState('employment_contract');
  const [title, setTitle] = useState('');
  const [number, setNumber] = useState('');
  const [signedOn, setSignedOn] = useState('');
  const [busy, setBusy] = useState(false);
  const [signers, setSigners] = useState<string[]>([]);
  const toggle = (p: string) => setSigners((s) => (s.includes(p) ? s.filter((x) => x !== p) : [...s, p]));
  const add = async () => {
    if (!file || !title.trim()) return;
    setBusy(true);
    try {
      await uploadForm(`/api/v1/employees/${employeeId}/documents`, file, { kind, title: title.trim(), number, signedOn, signers: signers.join(',') });
      toast('Документ добавлен, сотрудник получит уведомление', 'good');
      setFile(null); setTitle(''); setNumber(''); setSignedOn(''); setSigners([]);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    } finally {
      setBusy(false);
    }
  };
  const remove = async (d: EmployeeDocument) => {
    try {
      await api('DELETE', `/api/v1/employees/${employeeId}/documents/${d.id}`);
      reload();
    } catch (e) {
      toast(errorText(e), 'critical');
    }
  };
  return (
    <Modal open wide onClose={onClose} title={`Документы: ${name}`} footer={<Button variant="ghost" onClick={onClose}>Закрыть</Button>}>
      {error ? <Empty>{error}</Empty> : !data ? <Empty>Загрузка…</Empty> : (
        <DocumentList docs={data} open={(d) => { download(d.source === 'upload' ? `/api/v1/employees/${employeeId}/documents/${d.id}/file` : `/api/v1/me/documents/${d.id}/file`, d.fileName).catch((e) => toast(errorText(e), 'critical')); }} onRemove={(d) => { void remove(d); }} />
      )}
      <h4>Добавить документ</h4>
      <div className="form-row">
        <Field label="Вид">
          <select className="input" value={kind} onChange={(e) => setKind(e.target.value)}>
            {['employment_contract', 'agreement', 'order', 'other'].map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
          </select>
        </Field>
        <Field label="Название"><input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Трудовой договор" /></Field>
        <Field label="Номер"><input className="input" value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
        <Field label="Дата подписания"><input className="input" type="date" value={signedOn} onChange={(e) => setSignedOn(e.target.value)} /></Field>
        <Field label="Файл, до 20 МБ"><input className="input" type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
      </div>
      <fieldset className="sign-parties">
        <legend className="field-label">Кто подписывает электронной подписью (только PDF)</legend>
        {['employee', 'employer', 'accountant'].map((p) => (
          <label key={p} className="check"><input type="checkbox" checked={signers.includes(p)} onChange={() => toggle(p)} /> {PARTY_LABEL[p]}</label>
        ))}
      </fieldset>
      <Button variant="primary" disabled={!file || !title.trim() || busy} onClick={() => { void add(); }}>Добавить</Button>
    </Modal>
  );
}
