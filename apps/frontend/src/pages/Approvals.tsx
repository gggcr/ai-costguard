import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useLookup, useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import type { Approval } from '../api/types';
import { APPROVAL_META, Badge, Button, Card, Empty, Field, Modal, PageHeader, RiskBadge, Tabs } from '../components/ui';
import { fmtDateTime, fmtRelative, fmtMoney } from '../lib';

export function Approvals() {
  const { state, actions } = useStore();
  const { user, canEdit, canManageAgent } = useAuth();
  const L = useLookup();
  const [tab, setTab] = useState<'pending' | 'history'>('pending');
  const [deciding, setDeciding] = useState<{ ap: Approval; approve: boolean } | null>(null);
  const [comment, setComment] = useState('');
  const [, tick] = useState(0);
  useEffect(() => { const t = setInterval(() => tick((x) => x + 1), 30000); return () => clearInterval(t); }, []);

  const pending = state.approvals.filter((a) => a.status === 'pending');
  const history = state.approvals.filter((a) => a.status !== 'pending');
  // Финансист и владелец решают по всем агентам, владелец агента — только по своим
  const canDecide = (ap: Approval) => canEdit('approvals') && (user?.role !== 'agent_owner' || canManageAgent(L.agent(ap.agentId)));

  const list = tab === 'pending' ? pending : history;

  return (
    <>
      <PageHeader title="Согласования" subtitle="Операции дороже порога мандата ждут решения человека. Если срок истёк, резерв возвращается в бюджет" />
      <Tabs value={tab} onChange={setTab} items={[{ id: 'pending', label: `Ждут решения (${pending.length})` }, { id: 'history', label: `История (${history.length})` }]} />
      {list.length === 0 && <Card><Empty>{tab === 'pending' ? 'Все операции согласованы' : 'История пуста'}</Empty></Card>}
      <div className="stack">
        {list.map((ap) => {
          const agent = L.agent(ap.agentId);
          return (
            <Card key={ap.id}>
              <div className="approval">
                <div>
                  <div className="approval-amount">{fmtMoney(ap.amount)}</div>
                  <Badge tone={APPROVAL_META[ap.status].tone}>{APPROVAL_META[ap.status].label}</Badge>
                </div>
                <div className="approval-body">
                  <strong>{ap.description}</strong>
                  <div className="muted small">
                    <Link to={`/agents/${ap.agentId}`}>{agent?.name}</Link> · {L.dept(agent?.departmentId)?.name} · {fmtDateTime(ap.createdAt)}
                  </div>
                  <div className="small">Причина: {ap.reason}</div>
                  {agent && <div className="small row-gap">Агент сейчас: <RiskBadge score={agent.riskScore} /></div>}
                  {ap.status !== 'pending' && ap.decidedBy && <div className="small">Решение: {ap.decidedBy}{ap.comment ? ` — «${ap.comment}»` : ''}</div>}
                </div>
                {ap.status === 'pending' && (
                  <div className="approval-actions">
                    <span className="muted small">Срок решения {fmtRelative(ap.deadline)}</span>
                    {canDecide(ap) ? (
                      <div className="row-gap">
                        <Button variant="danger" onClick={() => { setDeciding({ ap, approve: false }); setComment(''); }}>Отклонить</Button>
                        <Button variant="primary" onClick={() => { setDeciding({ ap, approve: true }); setComment(''); }}>Одобрить</Button>
                      </div>
                    ) : <span className="muted small">Решает владелец агента или финансист</span>}
                  </div>
                )}
              </div>
            </Card>
          );
        })}
      </div>

      <Modal open={!!deciding} onClose={() => setDeciding(null)} title={deciding?.approve ? 'Одобрить операцию' : 'Отклонить операцию'} footer={
        <><Button variant="ghost" onClick={() => setDeciding(null)}>Отмена</Button>
          <Button variant={deciding?.approve ? 'primary' : 'danger'} disabled={!deciding?.approve && !comment.trim()}
            onClick={() => { if (deciding) actions.decideApproval(deciding.ap.id, deciding.approve, user?.name ?? '', comment); setDeciding(null); }}>
            {deciding?.approve ? 'Одобрить' : 'Отклонить'}
          </Button></>
      }>
        {deciding && (
          <div className="form">
            <p><strong>{fmtMoney(deciding.ap.amount)}</strong> · {deciding.ap.description}</p>
            <Field label={deciding.approve ? 'Комментарий (необязательно)' : 'Причина отказа'}>
              <textarea className="input" rows={3} value={comment} onChange={(e) => setComment(e.target.value)} />
            </Field>
            {deciding.approve && <p className="muted small">Решение подпишется и попадёт в журнал. Для крупных операций подтверждение уходит в контракт NEAR (confirm_operation).</p>}
          </div>
        )}
      </Modal>
    </>
  );
}
