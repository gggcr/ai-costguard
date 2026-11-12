// Простая электронная подпись документов (ADR 0068): ключ подписи — код из приложения-аутентификатора (Google
// Authenticator, Яндекс Ключ, Microsoft Authenticator) или ПИН-код из 6 цифр. Подписывают работник, работодатель
// (owner) и бухгалтерия (finance); скачанный PDF после подписи несёт штампы «Подписано электронной подписью».
import { useMemo, useState } from 'react';
import qrcode from 'qrcode-generator';
import { api, ApiError, download } from '../../api/http';
import { useStore } from '../../api/store';
import { Badge, Button, Card, Empty, Field, Modal } from '../../components/ui';
import { fmtDate, fmtDateTime } from '../../lib';
import { useApi } from './useApi';

export interface DocumentSignature { party: string; signerName: string; signerEmail: string; method: 'totp' | 'pin'; documentSha256: string; signedAt: string }
interface KeyStatus { method: 'totp' | 'pin' | null; totpAvailable: boolean; lockedUntil: string | null }
interface Awaiting { id: string; employeeId: string; employeeName: string; title: string; party: 'employer' | 'accountant'; createdAt: string; signers: string[]; signatures: DocumentSignature[] }

export const PARTY_LABEL: Record<string, string> = { employee: 'Работник', employer: 'Работодатель', accountant: 'Бухгалтерия' };

const SIGN_ERRORS: Record<string, string> = {
  signing_code_wrong: 'Неверный код',
  signing_locked: 'Пять неверных кодов подряд: подпись заблокирована на 15 минут',
  signing_key_missing: 'Сначала настройте подпись',
  signing_pin_weak: 'ПИН-код — 6 цифр, не одинаковые и не 123456',
  signing_no_pending: 'Начните настройку приложения заново',
  signing_key_unavailable: 'Сервер не может хранить ключ приложения: выберите ПИН-код',
  sign_already: 'Эта сторона уже подписала документ',
  sign_not_asked: 'Эту сторону не просили подписывать документ',
  sign_only_pdf: 'Подписывать можно только PDF',
};

export function signError(e: unknown): string {
  if (e instanceof ApiError) return (e.code && SIGN_ERRORS[e.code]) || e.message;
  return 'Сервер CostGuard не отвечает';
}

/** Строка подписей документа: кто подписал, кого ещё ждём. */
export function SignatureState({ signers, signatures }: { signers: string[]; signatures: DocumentSignature[] }) {
  if (signers.length === 0) return null;
  const done = new Map(signatures.map((s) => [s.party, s]));
  const all = signers.every((p) => done.has(p));
  return (
    <span className="sign-state">
      {all ? <Badge tone="good">Подписано электронной подписью</Badge> : <Badge tone="warning">Ждёт подписи</Badge>}
      {signers.map((p) => {
        const s = done.get(p);
        return <span key={p} className="muted small block">{PARTY_LABEL[p] ?? p}: {s ? `✓ ${s.signerName}, ${fmtDateTime(s.signedAt)}` : 'не подписал'}</span>;
      })}
    </span>
  );
}

function Qr({ text }: { text: string }) {
  const svg = useMemo(() => {
    const q = qrcode(0, 'M');
    q.addData(text);
    q.make();
    return q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
  }, [text]);
  return <div className="qr" role="img" aria-label="QR-код для приложения-аутентификатора" dangerouslySetInnerHTML={{ __html: svg }} />;
}

/** Настройка ключа подписи: приложение (QR) или ПИН-код. Смена ключа требует кода текущего. */
export function SigningKeySetup({ status, onDone }: { status: KeyStatus; onDone: () => void }) {
  const { toast } = useStore();
  const [mode, setMode] = useState<'totp' | 'pin'>(status.totpAvailable ? 'totp' : 'pin');
  const [current, setCurrent] = useState('');
  const [setup, setSetup] = useState<{ secret: string; uri: string } | null>(null);
  const [code, setCode] = useState('');
  const [pin, setPin] = useState('');
  const [pin2, setPin2] = useState('');
  const run = async (f: () => Promise<unknown>, ok?: string) => {
    try {
      await f();
      if (ok) { toast(ok, 'good'); onDone(); }
    } catch (e) {
      toast(signError(e), 'critical');
    }
  };
  return (
    <div className="signing-setup">
      <div className="row-gap">
        <Button variant={mode === 'totp' ? 'primary' : 'ghost'} disabled={!status.totpAvailable} onClick={() => setMode('totp')}>Приложение-аутентификатор</Button>
        <Button variant={mode === 'pin' ? 'primary' : 'ghost'} onClick={() => setMode('pin')}>ПИН-код</Button>
      </div>
      {status.method && (
        <Field label={status.method === 'totp' ? 'Код из приложения (текущий ключ)' : 'Текущий ПИН-код'}>
          <input className="input" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={current} onChange={(e) => setCurrent(e.target.value.replace(/\D/g, ''))} />
        </Field>
      )}
      {mode === 'totp' && (!setup ? (
        <Button variant="secondary" onClick={() => { void run(async () => setSetup(await api<{ secret: string; uri: string }>('POST', '/api/v1/me/signing-key/totp', { current: current || null }))); }}>Показать QR-код</Button>
      ) : (
        <>
          <p className="small">Отсканируйте код в Google Authenticator, Яндекс Ключе или Microsoft Authenticator. Или введите ключ вручную: <code>{setup.secret}</code></p>
          <Qr text={setup.uri} />
          <Field label="Код из приложения">
            <input className="input" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
          </Field>
          <Button variant="primary" disabled={code.length !== 6} onClick={() => { void run(() => api('PUT', '/api/v1/me/signing-key/totp', { code }), 'Подпись настроена: код из приложения'); }}>Подтвердить</Button>
        </>
      ))}
      {mode === 'pin' && (
        <>
          <Field label="Новый ПИН-код, 6 цифр"><input className="input" type="password" inputMode="numeric" maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} /></Field>
          <Field label="Ещё раз"><input className="input" type="password" inputMode="numeric" maxLength={6} value={pin2} onChange={(e) => setPin2(e.target.value.replace(/\D/g, ''))} /></Field>
          <Button variant="primary" disabled={pin.length !== 6 || pin !== pin2} onClick={() => { void run(() => api('PUT', '/api/v1/me/signing-key/pin', { pin, current: current || null }), 'Подпись настроена: ПИН-код'); }}>Сохранить ПИН-код</Button>
        </>
      )}
    </div>
  );
}

/** Карточка в профиле: какой ключ подписи настроен, сменить. */
export function SigningKeyCard() {
  const { data, reload } = useApi<KeyStatus>('/api/v1/me/signing-key');
  const [edit, setEdit] = useState(false);
  if (!data) return null;
  return (
    <Card title="Электронная подпись" actions={data.method && !edit && <Button variant="ghost" onClick={() => setEdit(true)}>Сменить</Button>}>
      <p className="small">{data.method === 'totp' ? 'Документы подписываются кодом из приложения-аутентификатора.' : data.method === 'pin' ? 'Документы подписываются ПИН-кодом.' : 'Подпись не настроена: выберите, чем будете подтверждать подпись документов.'}</p>
      {(!data.method || edit) && <SigningKeySetup status={data} onDone={() => { setEdit(false); reload(); }} />}
    </Card>
  );
}

/** Окно «Подписать»: ввод кода; если ключа нет — сначала настройка. */
export function SignModal({ title, path, onClose, onSigned }: { title: string; path: string; onClose: () => void; onSigned: () => void }) {
  const { toast } = useStore();
  const key = useApi<KeyStatus>('/api/v1/me/signing-key');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const sign = async () => {
    setBusy(true);
    try {
      await api('POST', path, { code });
      toast('Документ подписан электронной подписью', 'good');
      onSigned();
    } catch (e) {
      toast(signError(e), 'critical');
      setCode('');
    } finally {
      setBusy(false);
    }
  };
  const ready = key.data?.method;
  return (
    <Modal open onClose={onClose} title={`Подписать: ${title}`} footer={<><Button variant="ghost" onClick={onClose}>Отмена</Button>{ready && <Button variant="primary" disabled={code.length !== 6 || busy} onClick={() => { void sign(); }}>Подписать</Button>}</>}>
      {!key.data ? <Empty>Загрузка…</Empty> : !ready ? (
        <>
          <p className="small">Чтобы подписывать документы, настройте подпись: приложение-аутентификатор или ПИН-код.</p>
          <SigningKeySetup status={key.data} onDone={key.reload} />
        </>
      ) : (
        <>
          <p className="small">Подписывая, вы подтверждаете, что ознакомились с документом. Подпись — простая электронная (63-ФЗ): на документе появится штамп «Подписано электронной подписью».</p>
          <Field label={ready === 'totp' ? 'Код из приложения-аутентификатора' : 'ПИН-код подписи'}>
            <input className="input sign-code" type={ready === 'pin' ? 'password' : 'text'} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} onKeyDown={(e) => { if (e.key === 'Enter' && code.length === 6) void sign(); }} />
          </Field>
          {key.data.lockedUntil && <div className="alert alert-warning">Подпись заблокирована до {fmtDateTime(key.data.lockedUntil)}</div>}
        </>
      )}
    </Modal>
  );
}

/** Работодатель (owner) и бухгалтерия (finance): документы, которые ждут их подписи. */
export function AwaitingSignatures() {
  const { toast } = useStore();
  const { data, error, reload } = useApi<Awaiting[]>('/api/v1/documents/awaiting-signature');
  const [signing, setSigning] = useState<Awaiting | null>(null);
  return (
    <Card title="Документы на подпись">
      {signing && <SignModal title={signing.title} path={`/api/v1/documents/${signing.id}/signature/${signing.party}`} onClose={() => setSigning(null)} onSigned={() => { setSigning(null); reload(); }} />}
      {error ? <Empty>{error}</Empty> : !data ? <Empty>Загрузка…</Empty> : data.length === 0 ? <Empty>Подписывать нечего</Empty> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Сотрудник</th><th>Документ</th><th>Подписи</th><th /></tr></thead>
            <tbody>
              {data.map((d) => (
                <tr key={`${d.id}-${d.party}`}>
                  <td>{d.employeeName}<span className="muted small block">{fmtDate(d.createdAt)}</span></td>
                  <td>{d.title}</td>
                  <td><SignatureState signers={d.signers} signatures={d.signatures} /></td>
                  <td className="row-gap">
                    <Button variant="ghost" icon="download" onClick={() => { download(`/api/v1/employees/${d.employeeId}/documents/${d.id}/file`, `${d.title}.pdf`).catch((e) => toast(signError(e), 'critical')); }}>Открыть</Button>
                    <Button variant="primary" onClick={() => setSigning(d)}>{`Подписать как ${PARTY_LABEL[d.party].toLowerCase()}`}</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
