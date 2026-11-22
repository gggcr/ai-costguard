import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import type { GatewayCode } from '../api/types';
import { Badge, Button, Card, CODE_META, CodeBadge, Empty, PageHeader, RiskBadge } from '../components/ui';
import { LADDER } from './Policies';
import { currencySign, downloadText, fmtDateTime, fmtMoney, short, toCsv } from '../lib';

const PAGE = 25;

export function Operations() {
  const { state } = useStore();
  const L = useLookup();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const agent = params.get('agent') ?? 'all';
  const decision = params.get('decision') ?? 'all';
  const code = (params.get('code') ?? 'all') as GatewayCode | 'all';
  const [page, setPage] = useState(1);

  const set = (k: string, v: string) => { const p = new URLSearchParams(params); if (v === 'all') p.delete(k); else p.set(k, v); setParams(p); setPage(1); };

  const list = state.operations.filter((o) =>
    (agent === 'all' || o.agentId === agent) &&
    (decision === 'all' || (decision === 'allowed' ? o.code === 'allowed' : o.code !== 'allowed')) &&
    (code === 'all' || o.code === code));
  const total = list.filter((o) => o.code === 'allowed').reduce((s, o) => s + o.cost, 0);

  const exportCsv = () => {
    const rows: (string | number)[][] = [['ID', 'Время', 'Агент', 'Поставщик', 'Ресурс', 'Решение', `Сумма, ${currencySign()}`, 'Хэш квитанции', 'Статус в NEAR']];
    list.forEach((o) => rows.push([o.id, o.at, L.agent(o.agentId)?.name ?? o.agentId, o.vendor, o.resource, CODE_META[o.code].label, o.cost, o.receipt?.hash ?? '', o.receipt?.status === 'anchored' ? 'закреплена' : o.receipt ? 'ожидает пакета' : '']));
    downloadText('journal-operations.csv', toCsv(rows), 'text/csv');
  };

  return (
    <>
      <PageHeader title="Журнал операций" subtitle="Все решения шлюза: что агент хотел купить, разрешил ли шлюз и на какой проверке был отказ"
        actions={<Button icon="download" onClick={exportCsv}>Выгрузить CSV</Button>} />
      <div className="filters">
        <select className="input" value={agent} onChange={(e) => set('agent', e.target.value)} aria-label="Агент">
          <option value="all">Все агенты</option>
          {state.agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <select className="input" value={decision} onChange={(e) => set('decision', e.target.value)} aria-label="Решение">
          <option value="all">Все решения</option><option value="allowed">Разрешено</option><option value="denied">Отказы</option>
        </select>
        <select className="input" value={code} onChange={(e) => set('code', e.target.value)} aria-label="Код ответа">
          <option value="all">Все коды</option>
          {(Object.keys(CODE_META) as GatewayCode[]).map((c) => <option key={c} value={c}>{CODE_META[c].http} · {CODE_META[c].label}</option>)}
        </select>
        {(agent !== 'all' || decision !== 'all' || code !== 'all') && <Button variant="ghost" onClick={() => { setParams({}); setPage(1); }}>Сбросить</Button>}
        <span className="muted small filters-sum">{list.length} операций · разрешено на {fmtMoney(total)}</span>
      </div>
      <Card>
        {list.length === 0 ? <Empty>Операций по фильтру нет</Empty> : (
          <>
            <div className="table-wrap">
              <table>
                <thead><tr><th>ID</th><th>Время</th><th>Агент</th><th>Ресурс</th><th>Решение</th><th className="num">Риск</th><th className="num">Сумма</th><th>NEAR</th></tr></thead>
                <tbody>
                  {list.slice(0, page * PAGE).map((o) => (
                    <tr key={o.id} className="clickable" onClick={() => navigate(`/operations/${o.id}`)}>
                      <td><Link to={`/operations/${o.id}`} onClick={(e) => e.stopPropagation()}><code className="small">{o.id}</code></Link></td>
                      <td>{fmtDateTime(o.at)}</td>
                      <td>{L.agent(o.agentId)?.name ?? '—'}</td>
                      <td>{o.resource}<span className="muted small block">{o.vendor}</span></td>
                      <td><CodeBadge code={o.code} /></td>
                      <td className="num">{o.risk}</td>
                      <td className="num">{o.cost ? fmtMoney(o.cost) : '—'}</td>
                      <td>{o.receipt ? (o.receipt.status === 'anchored' ? <Badge tone="good">Закреплена</Badge> : <Badge tone="warning">В пакете</Badge>) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {list.length > page * PAGE && <div className="center"><Button onClick={() => setPage(page + 1)}>Показать ещё {Math.min(PAGE, list.length - page * PAGE)}</Button></div>}
          </>
        )}
      </Card>
    </>
  );
}

export function OperationDetail() {
  const { id } = useParams();
  const { state, actions, toast, mode } = useStore();
  const L = useLookup();
  const op = state.operations.find((o) => o.id === id);
  const [verify, setVerify] = useState<'idle' | 'running' | 'ok' | 'broken'>('idle');
  const [problem, setProblem] = useState('');
  // Список приносит только статус квитанции; хэши, подпись и путь Меркла — отдельным запросом
  const hasReceipt = !!op?.receipt;
  useEffect(() => { if (id && hasReceipt) void actions.loadReceipt(id); }, [id, hasReceipt, actions]);

  if (!op) return <Empty action={<Link to="/operations" className="btn btn-primary">В журнал</Link>}>Операция не найдена</Empty>;
  const agent = L.agent(op.agentId);
  const r = op.receipt;

  const runVerify = async () => {
    setVerify('running');
    if (mode === 'demo') { setTimeout(() => setVerify('ok'), 1200); return; }
    const v = await actions.verifyChain();
    setProblem(v?.problem ?? '');
    setVerify(v?.valid ? 'ok' : 'broken');
  };
  const downloadReceipt = () => {
    downloadText(`receipt-${op.id}.json`, JSON.stringify({ operation_id: op.id, agent_id: op.agentId, at: op.at, vendor: op.vendor, resource: op.resource, amount: op.cost, ...r }, null, 2), 'application/json');
  };

  return (
    <>
      <PageHeader back={{ to: '/operations', label: 'Журнал операций' }} title={<>Операция <code>{op.id}</code></>} subtitle={fmtDateTime(op.at)} actions={<CodeBadge code={op.code} />} />
      <div className="grid-2">
        <Card title="Что произошло">
          <dl className="dl">
            <dt>Агент</dt><dd>{agent ? <Link to={`/agents/${agent.id}`}>{agent.name}</Link> : '—'}</dd>
            <dt>Поставщик</dt><dd>{op.vendor}</dd>
            <dt>Ресурс</dt><dd>{op.resource}</dd>
            <dt>Сумма</dt><dd>{op.cost ? fmtMoney(op.cost) : 'не списано'}</dd>
            <dt>Риск в момент операции</dt><dd><RiskBadge score={op.risk} /></dd>
            <dt>Ответ шлюза</dt><dd>HTTP {CODE_META[op.code].http} · {CODE_META[op.code].label}</dd>
          </dl>
        </Card>
        <Card title="Лестница проверок">
          <ol className="ladder compact">
            {LADDER.map((s) => {
              const failed = op.failedStep === s.n;
              const passed = !op.failedStep || s.n < op.failedStep;
              const skipped = op.code === 'allowed' && (s.n === 6 || s.n === 7) && op.cost < 300;
              return (
                <li key={s.n} className={failed ? 'failed' : passed ? 'passed' : 'skipped'}>
                  <span className="ladder-n">{failed ? '×' : passed && !skipped ? '✓' : '–'}</span>
                  <span><strong>{s.name}</strong><span className="muted small block">{failed ? `Отказ: ${s.fail}` : skipped ? 'Не требуется для небольшой суммы' : passed ? s.text : 'Не выполнялась'}</span></span>
                </li>
              );
            })}
          </ol>
        </Card>
      </div>

      <Card title="Квитанция и подтверждение в NEAR" actions={r && <Button icon="download" onClick={downloadReceipt}>Скачать квитанцию</Button>}>
        {!r ? <p className="muted">Для отказов квитанция не выпускается: деньги не тратились.</p> : (
          <>
            <dl className="dl mono">
              <dt>Хэш квитанции</dt><dd><code>{r.hash}</code></dd>
              <dt>Подпись Ed25519</dt><dd><code>{short(r.signature, 24)}</code></dd>
              <dt>Предыдущий хэш</dt><dd><code>{short(r.prevHash, 16)}</code></dd>
              <dt>Статус</dt><dd>{r.status === 'anchored' ? <Badge tone="good">Закреплена в NEAR</Badge> : <Badge tone="warning">Ждёт пакета (раз в 60 с)</Badge>}</dd>
              {r.batchId && <><dt>Пакет</dt><dd><code>{r.batchId}</code></dd></>}
              {r.txHash && <><dt>Транзакция</dt><dd><code>{short(r.txHash, 14)}</code></dd></>}
              {r.merklePath && <><dt>Путь Меркла</dt><dd>{r.merklePath.map((p) => <code key={p} className="block">{short(p, 12)}</code>)}</dd></>}
            </dl>
            <div className="row-gap wrap">
              {r.status === 'pending' && mode === 'demo' && <Button onClick={() => { const n = actions.anchorPending(); toast(`Нотариус закрепил пакет: ${n} квитанций`, 'good'); }}>Закрепить пакет сейчас (демо)</Button>}
              {r.status === 'anchored' && <Button variant="primary" onClick={runVerify} disabled={verify === 'running'}>{verify === 'running' ? 'Проверяю…' : 'Проверить в NEAR'}</Button>}
            </div>
            {verify === 'ok' && mode === 'api' && (
              <div className="alert alert-good">
                <strong>✓ Журнал квитанций цел.</strong> Ядро проверило всю цепочку: каждый хэш и каждую подпись Ed25519. Квитанция закреплена в NEAR транзакцией <code>{r.txHash}</code>, корень Меркла пакета <code>{r.batchId}</code>.
              </div>
            )}
            {verify === 'broken' && (
              <div className="alert alert-critical" role="alert"><strong>Цепочка квитанций нарушена.</strong> {problem}</div>
            )}
            {verify === 'ok' && mode === 'demo' && (
              <div className="alert alert-good">
                <strong>✓ Квитанция подлинная.</strong> Корень Меркла, вычисленный из квитанции и пути, совпал с корнем пакета <code>{r.batchId}</code> в контракте <code>costguard-notary</code> (вызов <code>get_batch</code>). Суммы и персональные данные в блокчейн не попадали.
              </div>
            )}
          </>
        )}
      </Card>
    </>
  );
}
