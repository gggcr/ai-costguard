import { useRef, useState, type DragEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { FOCUS_FIELDS, useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { ImportJob, ImportType } from '../api/types';
import { Badge, Button, Card, ConfirmButton, Empty, IMPORT_META, PageHeader, ReadOnlyNote } from '../components/ui';
import { downloadText, fmtDateTime, fmtRub, toCsv } from '../lib';

const TYPE_LABEL: Record<ImportType, string> = { excel: 'Excel', csv: 'CSV', pdf: 'PDF с текстом', scan: 'Скан или фото', bank: 'Банковская выписка' };
const ROUTE: Record<ImportType, string> = {
  excel: 'Шаблон поставщика или поиск заголовков по словарю синонимов',
  csv: 'Шаблон поставщика или поиск заголовков по словарю синонимов',
  pdf: 'pdfplumber: текст и таблицы → LLM-извлечение в JSON-схему счёта',
  scan: 'OCR PaddleOCR и разметка → LLM-извлечение в JSON-схему счёта',
  bank: 'Парсер 1CClientBankExchange или MT940',
};

export function Imports() {
  const { state, actions } = useStore();
  const { user, canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const input = useRef<HTMLInputElement>(null);
  const [vendorId, setVendorId] = useState('');
  const [drag, setDrag] = useState(false);
  const edit = canEdit('imports');

  const upload = (files: FileList | null) => {
    const f = files?.[0];
    if (!f) return;
    const id = actions.createImport(f.name, user?.name ?? '', vendorId || undefined);
    navigate(`/imports/${id}`);
  };
  const onDrop = (e: DragEvent) => { e.preventDefault(); setDrag(false); if (edit) upload(e.dataTransfer.files); };

  return (
    <>
      <PageHeader title="Импорт данных" subtitle="Загрузите счета, выгрузки лицензий и банковские выписки: система разберёт их в единые записи затрат" />
      {!edit && <ReadOnlyNote />}
      {edit && (
        <Card>
          <div className={`dropzone ${drag ? 'drag' : ''}`} onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)} onDrop={onDrop}>
            <strong>Перетащите файл сюда</strong>
            <span className="muted small">Excel, CSV, PDF, скан или фото счёта, выписка 1С (txt) или MT940</span>
            <div className="row-gap wrap center">
              <select className="input" value={vendorId} onChange={(e) => setVendorId(e.target.value)} aria-label="Поставщик">
                <option value="">Поставщик не указан</option>
                {state.vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
              </select>
              <Button variant="primary" icon="imports" onClick={() => input.current?.click()}>Выбрать файл</Button>
              <input ref={input} type="file" hidden accept=".xlsx,.xls,.csv,.pdf,.jpg,.jpeg,.png,.txt,.sta,.940" onChange={(e) => { upload(e.target.files); e.target.value = ''; }} />
            </div>
            <span className="muted small">Если для поставщика уже сохранён шаблон, Excel разберётся без вопросов</span>
          </div>
        </Card>
      )}
      <Card title="Загрузки">
        {state.imports.length === 0 ? <Empty>Файлов пока нет</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Файл</th><th>Тип</th><th>Поставщик</th><th>Загрузил</th><th>Статус</th><th className="num">Строк</th><th className="num">Сумма</th></tr></thead>
              <tbody>
                {state.imports.map((j) => (
                  <tr key={j.id} className="clickable" onClick={() => navigate(`/imports/${j.id}`)}>
                    <td><Link to={`/imports/${j.id}`} onClick={(e) => e.stopPropagation()}>{j.fileName}</Link><span className="muted small block">{fmtDateTime(j.uploadedAt)}</span></td>
                    <td>{TYPE_LABEL[j.type]}</td>
                    <td>{L.vendor(j.vendorId)?.name ?? '—'}</td>
                    <td>{j.uploadedBy}</td>
                    <td><Badge tone={IMPORT_META[j.status].tone}>{IMPORT_META[j.status].label}</Badge>{j.errors.length > 0 && j.status === 'done' && <span className="muted small block">ошибок: {j.errors.length}</span>}</td>
                    <td className="num">{j.rows || '—'}</td>
                    <td className="num">{j.total ? fmtRub(j.total) : '—'}</td>
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

function Pipeline({ job }: { job: ImportJob }) {
  const steps = ['Bronze: файл в S3', 'Разбор', 'Проверки: схема, суммы, ИНН, даты, дубли', 'Silver: записи FOCUS', 'Движок аудита'];
  const reached = job.status === 'failed' ? 1 : job.status === 'mapping' ? 1 : job.status === 'review' ? 2 : 5;
  return (
    <ol className="pipeline">
      {steps.map((s, i) => (
        <li key={s} className={i < reached ? 'done' : i === reached ? (job.status === 'failed' ? 'failed' : 'current') : ''}>
          <span className="pipeline-dot">{i < reached ? '✓' : i === reached && job.status === 'failed' ? '×' : i + 1}</span>
          <span className="small">{s}</span>
        </li>
      ))}
    </ol>
  );
}

export function ImportDetail() {
  const { id } = useParams();
  const { state, actions } = useStore();
  const { canEdit } = useAuth();
  const L = useLookup();
  const navigate = useNavigate();
  const job = state.imports.find((j) => j.id === id);
  const [mapping, setMapping] = useState<Record<string, string>>(job?.mapping ?? {});
  const [saveTemplate, setSaveTemplate] = useState(true);
  const [fixes, setFixes] = useState<Record<string, string>>({});
  if (!job) return <Empty action={<Link to="/imports" className="btn btn-primary">К загрузкам</Link>}>Импорт не найден</Empty>;
  const edit = canEdit('imports');

  const errorReport = () => {
    downloadText(`errors-${job.fileName}.csv`, toCsv([['Строка', 'Ошибка'], ...job.errors.map((e) => [e.row || '—', e.message])]), 'text/csv');
  };

  return (
    <>
      <PageHeader back={{ to: '/imports', label: 'Импорт данных' }} title={job.fileName}
        subtitle={`${TYPE_LABEL[job.type]} · ${L.vendor(job.vendorId)?.name ?? 'поставщик не указан'} · ${job.uploadedBy}, ${fmtDateTime(job.uploadedAt)}`}
        actions={<>
          <Badge tone={IMPORT_META[job.status].tone}>{IMPORT_META[job.status].label}</Badge>
          {edit && <ConfirmButton variant="ghost" title="Удалить импорт?" text="Файл и созданные из него записи затрат будут удалены." confirmLabel="Удалить" onConfirm={() => { actions.deleteImport(job.id); navigate('/imports'); }}>Удалить</ConfirmButton>}
        </>} />
      <Card><Pipeline job={job} /><p className="muted small">Маршрут разбора: {ROUTE[job.type]}</p></Card>

      {job.status === 'mapping' && (
        <Card title="Сопоставьте колонки файла с полями">
          <p className="muted small">Шаблон этого поставщика не найден. Мы подобрали поля по заголовкам и словарю синонимов, проверьте их.</p>
          <div className="mapping">
            {job.columns.map((c) => (
              <div key={c} className="mapping-row">
                <span className="mapping-col">{c}</span>
                <span aria-hidden="true">→</span>
                <select className="input" value={mapping[c] ?? '— не импортировать —'} disabled={!edit} onChange={(e) => setMapping({ ...mapping, [c]: e.target.value })} aria-label={`Поле для колонки ${c}`}>
                  {FOCUS_FIELDS.map((f) => <option key={f}>{f}</option>)}
                </select>
              </div>
            ))}
          </div>
          {edit && (
            <div className="row-gap wrap">
              <label className="switch"><input type="checkbox" checked={saveTemplate} onChange={(e) => setSaveTemplate(e.target.checked)} /> <span>Сохранить как шаблон поставщика</span></label>
              <Button variant="primary" onClick={() => actions.confirmMapping(job.id, mapping, saveTemplate)}>Подтвердить и импортировать</Button>
            </div>
          )}
        </Card>
      )}

      {job.status === 'review' && (
        <Card title={`Ручная проверка: ${job.reviewTasks.filter((t) => !t.resolved).length} полей`}>
          <p className="muted small">Эти поля распознаны с низкой уверенностью. Исправления сохранятся как пример поставщика, и в следующий раз система ошибётся реже.</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Строка</th><th>Поле</th><th>Распознано</th><th className="num">Уверенность</th><th>Исправить</th><th /></tr></thead>
              <tbody>
                {job.reviewTasks.map((t) => (
                  <tr key={t.id}>
                    <td>{t.row}</td><td>{t.field}</td><td><code>{t.value}</code></td>
                    <td className="num">{Math.round(t.confidence * 100)}%</td>
                    <td>{t.resolved ? <Badge tone="good">Исправлено</Badge> : <input className="input" disabled={!edit} value={fixes[t.id] ?? t.value.replace(/[OО]/g, '0')} onChange={(e) => setFixes({ ...fixes, [t.id]: e.target.value })} aria-label={`Исправленное значение, строка ${t.row}`} />}</td>
                    <td>{!t.resolved && edit && <Button onClick={() => actions.resolveTask(job.id, t.id, fixes[t.id] ?? t.value.replace(/[OО]/g, '0'))}>Сохранить</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {job.status === 'failed' && (
        <Card title="Не удалось разобрать файл">
          <ul className="bullets">{job.errors.map((e) => <li key={e.message}>{e.message}</li>)}</ul>
          {edit && <Button variant="primary" icon="refresh" onClick={() => actions.retryImport(job.id)}>Распознать повторно</Button>}
        </Card>
      )}

      {job.status === 'done' && (
        <div className="grid-2">
          <Card title="Результат">
            <dl className="dl">
              <dt>Строк импортировано</dt><dd>{job.rows - job.errors.filter((e) => e.row > 0).length} из {job.rows}</dd>
              <dt>Сумма</dt><dd>{fmtRub(job.total)}</dd>
              <dt>Шаблон поставщика</dt><dd>{job.templateSaved ? <Badge tone="good">сохранён</Badge> : <Badge>не сохранён</Badge>}</dd>
            </dl>
            <p className="muted small">Записи затрат переданы в ClickHouse (Silver) и в движок аудита. Новые находки появятся в разделе «Находки».</p>
            <Link className="link" to="/findings">К находкам →</Link>
          </Card>
          <Card title={`Ошибки: ${job.errors.length}`} actions={job.errors.length > 0 && <Button icon="download" onClick={errorReport}>Отчёт об ошибках</Button>}>
            {job.errors.length === 0 ? <p className="muted">Ошибок нет</p> : <ul className="bullets">{job.errors.map((e) => <li key={e.row + e.message}>{e.row ? `Строка ${e.row}: ` : ''}{e.message}</li>)}</ul>}
          </Card>
        </div>
      )}
      {job.status === 'done' && Object.keys(job.mapping).length > 0 && (
        <Card title="Сопоставление колонок">
          <div className="mapping">{Object.entries(job.mapping).map(([c, f]) => <div key={c} className="mapping-row"><span className="mapping-col">{c}</span><span aria-hidden="true">→</span><span>{f}</span></div>)}</div>
        </Card>
      )}
    </>
  );
}
