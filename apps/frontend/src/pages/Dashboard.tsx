import { Link } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { Badge, Card, CodeBadge, FindingBadge, PageHeader, Progress, RiskBadge, Stat } from '../components/ui';
import { BarList } from '../components/charts';
import { fmtRelative, fmtMoney } from '../lib';

export function Dashboard() {
  const { state } = useStore();
  const { user, canView } = useAuth();
  const L = useLookup();

  const company = state.budgets.find((b) => b.level === 'company');
  const softwareMonthly = state.subscriptions.reduce((s, x) => s + x.seats * x.pricePerSeat, 0);
  const openFindings = state.findings.filter((f) => ['new', 'review', 'accepted', 'in_progress', 'postponed'].includes(f.status));
  const potential = openFindings.reduce((s, f) => s + f.expectedSavings, 0);
  const confirmed = state.findings.filter((f) => f.status === 'confirmed').reduce((s, f) => s + f.expectedSavings, 0);
  const pending = state.approvals.filter((a) => a.status === 'pending');
  const blocked = state.operations.filter((o) => o.code !== 'allowed').slice(0, 5);
  const riskyAgents = [...state.agents].sort((a, b) => b.riskScore - a.riskScore).slice(0, 4);

  const byDept = state.departments.map((d) => {
    const agents = state.budgets.find((b) => b.level === 'department' && b.refId === d.id)?.spent ?? 0;
    const software = state.subscriptions.filter((s) => s.departmentId === d.id).reduce((s, x) => s + x.seats * x.pricePerSeat, 0);
    return { label: d.name, value: Math.round(agents + software) };
  }).sort((a, b) => b.value - a.value);

  return (
    <>
      <PageHeader title={`Добрый день, ${user?.name.split(' ')[0]}`} subtitle={`Сводка цифровых расходов компании за ${new Date().toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}`} />

      <div className="stats">
        <Stat label="ИИ-агенты: потрачено в ноябре" value={fmtMoney(company?.spent ?? 0)} hint={company && <>из {fmtMoney(company.limit)} · <Progress value={company.spent} max={company.limit} /></>} to={canView('budgets') ? '/budgets' : undefined} />
        <Stat label="Подписки на ПО в месяц" value={fmtMoney(softwareMonthly)} hint={`${state.subscriptions.length} подписок`} to={canView('software') ? '/software' : undefined} />
        <Stat label="Потенциальная экономия" value={fmtMoney(potential)} hint={`${openFindings.length} открытых находок, в месяц`} to={canView('findings') ? '/findings' : undefined} />
        <Stat label="Подтверждённая экономия" value={fmtMoney(confirmed)} hint="видна в следующих счетах" to={canView('findings') ? '/findings?status=confirmed' : undefined} />
      </div>

      <div className="grid-2">
        <Card title="Расходы по отделам в месяц" actions={canView('analytics') && <Link to="/analytics" className="link">Прогнозы →</Link>}>
          <p className="muted small">ИИ-агенты (факт за ноябрь) + подписки на ПО</p>
          <BarList items={byDept} />
        </Card>

        {canView('approvals') ? (
          <Card title={<>Ждут согласования <Badge tone={pending.length ? 'warning' : 'good'}>{pending.length}</Badge></>} actions={<Link to="/approvals" className="link">Все →</Link>}>
            {pending.length === 0 && <p className="muted">Нет операций на согласовании</p>}
            <ul className="list">
              {pending.slice(0, 4).map((a) => (
                <li key={a.id}>
                  <Link to="/approvals" className="list-row">
                    <span>
                      <strong>{fmtMoney(a.amount)}</strong> · {L.agent(a.agentId)?.name}
                      <span className="muted small block">{a.description}</span>
                    </span>
                    <span className="muted small">до решения {fmtRelative(a.deadline)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </Card>
        ) : (
          <Card title="Агенты с высоким риском">
            <ul className="list">
              {riskyAgents.map((a) => (
                <li key={a.id}><Link to={`/agents/${a.id}`} className="list-row"><span>{a.name}<span className="muted small block">{a.riskReason}</span></span><RiskBadge score={a.riskScore} /></Link></li>
              ))}
            </ul>
          </Card>
        )}
      </div>

      <div className="grid-2">
        {canView('findings') && (
          <Card title="Крупнейшие находки" actions={<Link to="/findings" className="link">Все →</Link>}>
            <ul className="list">
              {[...openFindings].sort((a, b) => b.expectedSavings - a.expectedSavings).slice(0, 5).map((f) => (
                <li key={f.id}>
                  <Link to={`/findings/${f.id}`} className="list-row">
                    <span>{f.title}<span className="block"><FindingBadge status={f.status} /></span></span>
                    <strong>{fmtMoney(f.expectedSavings)}<span className="muted small">/мес</span></strong>
                  </Link>
                </li>
              ))}
            </ul>
          </Card>
        )}
        <Card title="Последние отказы шлюза" actions={<Link to="/operations?decision=denied" className="link">Журнал →</Link>}>
          <ul className="list">
            {blocked.map((o) => (
              <li key={o.id}>
                <Link to={`/operations/${o.id}`} className="list-row">
                  <span>{L.agent(o.agentId)?.name}<span className="muted small block">{o.resource} · {fmtRelative(o.at)}</span></span>
                  <CodeBadge code={o.code} />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      {canView('approvals') && (
        <Card title="Агенты с высоким риском" actions={<Link to="/agents" className="link">Все агенты →</Link>}>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Агент</th><th>Отдел</th><th>Причина</th><th>Статус</th><th className="num">Риск</th></tr></thead>
              <tbody>
                {riskyAgents.map((a) => (
                  <tr key={a.id}>
                    <td><Link to={`/agents/${a.id}`}>{a.name}</Link></td>
                    <td>{L.dept(a.departmentId)?.name}</td>
                    <td className="muted">{a.riskReason}</td>
                    <td>{a.status === 'active' ? <Badge tone="good">Работает</Badge> : <Badge tone="critical">Остановлен</Badge>}</td>
                    <td className="num"><RiskBadge score={a.riskScore} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}
