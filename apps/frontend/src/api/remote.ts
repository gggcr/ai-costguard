// Данные из REST API ядра (schemas/openapi/core.yaml → generated/core.ts) в форме AppState, которую рисуют страницы.
// Каждое действие — запрос к ядру и перечитывание затронутых разделов: источник истины всегда ядро.
import type * as Api from './generated/core';
import { api, upload } from './http';
import type {
  Agent, AppState, Approval, Asset, AssetAssignment, Budget, Connection, ConnectionRun, Contract, Employee, Finding,
  FindingStatus, GatewayCode, ImportJob, LicenseAssignment, Mandate, Notification, Operation, Role, Subscription, SystemType,
  User, Vendor, VirtualKey,
} from './types';

export type Slice = keyof AppState | 'systemTypes' | 'company';

export interface RemoteExtras {
  systemTypes: SystemType[];
  company: { name: string; currency: string };
}

export const EMPTY_STATE: AppState = {
  users: [], departments: [], employees: [], agents: [], keys: [], mandates: [], budgets: [], operations: [], approvals: [],
  vendors: [], contracts: [], subscriptions: [], assignments: [], assets: [], assetAssignments: [], findings: [], imports: [],
  notifications: [], reports: [], costHistory: [], connections: [], connectionRuns: [],
};

const s = (v: string | undefined | null) => v ?? '';
const n = (v: number | undefined | null) => (typeof v === 'number' ? v : 0);

// ——— Преобразование ответов ядра ———

export const toUser = (u: Api.UserAccount): User => ({ id: s(u.id), name: s(u.name), email: s(u.email), role: (u.role ?? 'auditor') as Role, employeeId: u.employeeId });
const toEmployee = (e: Api.Employee): Employee => ({ id: s(e.id), name: s(e.name), email: s(e.email), departmentId: s(e.departmentId), position: s(e.position), active: e.active ?? true });
const toAgent = (a: Api.Agent): Agent => ({
  id: s(a.id), name: s(a.name), description: s(a.description), framework: s(a.framework), departmentId: s(a.departmentId),
  ownerId: s(a.ownerId), status: a.status ?? 'active', riskScore: n(a.riskScore), riskReason: s(a.riskReason), mandateId: s(a.mandateId),
  createdAt: s(a.createdAt),
});
const toKey = (k: Api.VirtualKey): VirtualKey => ({ id: s(k.id), agentId: s(k.agentId), prefix: s(k.prefix), createdAt: s(k.createdAt), status: k.status ?? 'active' });
const toMandate = (m: Api.Mandate): Mandate => ({
  id: s(m.id), name: s(m.name), models: m.models ?? [], vendors: m.vendors ?? [], maxPerOperation: n(m.maxPerOperation),
  approvalThreshold: n(m.approvalThreshold), hours: s(m.hours), status: m.status ?? 'draft', version: n(m.version), updatedAt: s(m.updatedAt),
});
// Резерв живёт в Redis шлюза, ядро его не отдаёт
const toBudget = (b: Api.Budget): Budget => ({ id: s(b.id), name: s(b.name), level: b.level ?? 'company', refId: b.refId, parentId: b.parentId, limit: n(b.limit), spent: n(b.spent), reserved: 0 });
const toApproval = (a: Api.Approval): Approval => ({
  id: s(a.id), agentId: s(a.agentId), description: s(a.description), amount: n(a.amount), reason: s(a.reason), createdAt: s(a.createdAt),
  deadline: s(a.deadline), status: a.status ?? 'pending', decidedBy: a.decidedBy, comment: a.comment,
});
const toVendor = (v: Api.Vendor): Vendor => ({ id: s(v.id), name: s(v.name), inn: s(v.inn), category: s(v.category) });
const toContract = (c: Api.Contract): Contract => ({
  id: s(c.id), vendorId: s(c.vendorId), number: s(c.number), startDate: s(c.startDate), endDate: s(c.endDate), noticeDays: n(c.noticeDays),
  autoRenew: c.autoRenew ?? false, amount: n(c.amount),
});
const toSubscription = (x: Api.Subscription): Subscription => ({
  id: s(x.id), vendorId: s(x.vendorId), product: s(x.product), contractId: x.contractId, departmentId: s(x.departmentId), seats: n(x.seats),
  pricePerSeat: n(x.pricePerSeat), billingPeriod: x.billingPeriod ?? 'month', renewalDate: s(x.renewalDate),
});
const toLicense = (l: Api.License): LicenseAssignment => ({ id: s(l.id), subscriptionId: s(l.subscriptionId), employeeId: s(l.employeeId), lastActive: l.lastActive ?? null });

interface ApiAssignment { id: string; assetId: string; employeeId: string; issuedAt: string; returnDue: string | null; returnedAt: string | null; actNumber: string | null }
interface ApiAsset { id: string; inventoryNumber: string; model: string; type: Asset['type']; cost: number; serialNumber: string | null; status: Asset['status']; currentAssignment: ApiAssignment | null }

const toAssignment = (a: ApiAssignment): AssetAssignment => ({
  id: a.id, assetId: a.assetId, employeeId: a.employeeId, issuedAt: a.issuedAt, returnDue: a.returnDue ?? null, returnedAt: a.returnedAt ?? null,
  actNumber: a.actNumber ?? null, issuedBy: '—',
});
const toAsset = (a: ApiAsset): Asset => ({
  id: a.id, inventoryNumber: a.inventoryNumber, model: a.model, type: a.type ?? 'OTHER', cost: n(a.cost), serialNumber: a.serialNumber ?? undefined,
  status: a.status, source: 'manual', createdAt: a.currentAssignment?.issuedAt ?? '',
});
const toFinding = (f: Api.Finding): Finding => ({
  id: s(f.id), rule: s(f.rule), title: s(f.title), description: s(f.description), subscriptionId: f.subscriptionId, agentId: f.agentId,
  expectedSavings: n(f.expectedSavings), status: (f.status ?? 'new') as FindingStatus, ownerId: f.ownerId, postponedUntil: f.postponedUntil,
  rejectReason: f.rejectReason, missedInvoices: n(f.missedInvoices), createdAt: s(f.createdAt),
  history: (f.history ?? []).map((h) => ({ at: s(h.at), by: s(h.by), text: s(h.text) })),
});
const toImport = (j: Api.ImportJob): ImportJob => ({
  id: s(j.id), fileName: s(j.fileName), type: j.type ?? 'excel', vendorId: j.vendorId, uploadedAt: s(j.uploadedAt), uploadedBy: s(j.uploadedBy),
  status: j.status ?? 'uploaded', rows: n(j.rows), total: n(j.total), columns: j.columns ?? [], mapping: j.mapping ?? {},
  errors: (j.errors ?? []).map((e) => ({ row: n(e.row), message: s(e.message) })),
  reviewTasks: (j.reviewTasks ?? []).map((t) => ({ id: s(t.id), row: n(t.row), field: s(t.field), value: t.resolvedValue ?? s(t.value), confidence: n(t.confidence), resolved: t.resolved ?? false })),
  templateSaved: j.templateSaved ?? false, failure: j.failure,
});
const toNotification = (x: Api.Notification): Notification => ({ id: s(x.id), at: s(x.at), text: s(x.text), link: x.link, level: x.level ?? 'info', read: x.read ?? false });
const toConnection = (c: Api.Connection): Connection => ({
  id: s(c.id), type: s(c.type), name: s(c.name), status: c.status ?? 'pending', enabled: c.enabled ?? false, managedInCore: c.managedInCore ?? true,
  settings: c.settings ?? {}, recordsTotal: n(c.recordsTotal), createdAt: s(c.createdAt), lastSyncAt: c.lastSyncAt, recordsLastSync: c.recordsLastSync,
  lastError: c.lastError, lastErrorAt: c.lastErrorAt, syncRequestedAt: c.syncRequestedAt,
});
const toRun = (connectionId: string, r: Api.ConnectionRun): ConnectionRun => ({
  id: s(r.id), connectionId, startedAt: s(r.startedAt), finishedAt: r.finishedAt, status: s(r.status), records: n(r.records), error: r.error,
});
const toSystemType = (t: Api.SystemType): SystemType => ({ type: s(t.type), title: s(t.title), kind: s(t.kind), required: t.required ?? [], optional: t.optional ?? [], secrets: t.secrets ?? [] });

// Решение шлюза → код в интерфейсе и ступень лестницы, на которой отказ
const DENIAL: Record<string, [GatewayCode, number]> = {
  invalid_api_key: ['401_unauthorized', 1],
  policy_denied: ['403_policy_denied', 2],
  duplicate_operation: ['409_duplicate_operation', 3],
  agent_suspended: ['423_agent_suspended', 4],
  rate_limited: ['429_rate_limited', 4],
  budget_exceeded: ['402_budget_exceeded', 5],
  approval_rejected: ['approval_rejected', 7],
  approval_expired: ['approval_rejected', 7],
  approval_used: ['approval_rejected', 7],
};

/** Решение шлюза из GET /api/v1/operations (ядро читает ClickHouse). */
export type ApiOperation = Api.OperationView;

export function toOperation(o: ApiOperation): Operation {
  const [code, step]: [GatewayCode, number | undefined] = o.decision === 'ALLOWED' ? ['allowed', undefined]
    : o.decision === 'PENDING_APPROVAL' ? ['pending_approval', 7]
      : DENIAL[o.denialCode ?? ''] ?? ['403_policy_denied', 2];
  return {
    id: s(o.id), agentId: s(o.agentId), at: s(o.at), vendor: s(o.provider), resource: o.model ?? (o.kind === 'PAID_API' ? 'Платный API' : 'LLM'),
    cost: o.decision === 'ALLOWED' ? n(o.cost ?? o.estimatedCost) : 0, code, risk: 0, failedStep: step,
    receipt: o.receipt ? { hash: '', signature: '', prevHash: '', status: o.receipt } : undefined,
  };
}

// ——— Загрузка разделов ———

const all = <T,>(xs: Promise<T>[]) => Promise.all(xs);

async function loadAssets(): Promise<Pick<AppState, 'assets' | 'assetAssignments'>> {
  const items: ApiAsset[] = [];
  for (let page = 0; page < 100; page++) {
    const p = await api<{ items: ApiAsset[] }>('GET', '/api/v1/assets', undefined, { page, size: 200 });
    items.push(...p.items);
    if (p.items.length < 200) break;
  }
  return {
    assets: items.map(toAsset),
    assetAssignments: items.filter((a) => a.currentAssignment).map((a) => toAssignment(a.currentAssignment!)),
  };
}

type Loaded = Partial<AppState> & Partial<RemoteExtras>;

/** Какие разделы загружает каждый запрос. Ключи AppState, которые ядро не хранит (reports), остаются пустыми. */
export const LOADERS: Partial<Record<Slice, (st: AppState) => Promise<Loaded>>> = {
  company: async () => {
    const c = await api<Api.Company>('GET', '/api/v1/company');
    return { company: { name: s(c.name), currency: c.currency ?? 'RUB' } };
  },
  users: async () => ({ users: (await api<Api.UserAccount[]>('GET', '/api/v1/users')).map(toUser) }),
  departments: async () => ({ departments: (await api<Api.Department[]>('GET', '/api/v1/departments')).map((d) => ({ id: s(d.id), name: s(d.name) })) }),
  employees: async () => ({ employees: (await api<Api.Employee[]>('GET', '/api/v1/employees')).map(toEmployee) }),
  agents: async () => {
    const agents = (await api<Api.Agent[]>('GET', '/api/v1/agents')).map(toAgent);
    const keys = await all(agents.map((a) => api<Api.VirtualKey[]>('GET', `/api/v1/agents/${a.id}/keys`).catch(() => [])));
    return { agents, keys: keys.flat().map(toKey) };
  },
  mandates: async () => ({ mandates: (await api<Api.Mandate[]>('GET', '/api/v1/mandates')).map(toMandate) }),
  budgets: async () => ({ budgets: (await api<Api.Budget[]>('GET', '/api/v1/budgets')).map(toBudget) }),
  operations: async () => ({ operations: (await api<ApiOperation[]>('GET', '/api/v1/operations', undefined, { limit: 500 })).map(toOperation) }),
  approvals: async () => ({ approvals: (await api<Api.Approval[]>('GET', '/api/v1/approvals')).map(toApproval) }),
  vendors: async () => ({ vendors: (await api<Api.Vendor[]>('GET', '/api/v1/vendors')).map(toVendor) }),
  contracts: async () => ({ contracts: (await api<Api.Contract[]>('GET', '/api/v1/contracts')).map(toContract) }),
  subscriptions: async () => {
    const subscriptions = (await api<Api.Subscription[]>('GET', '/api/v1/subscriptions')).map(toSubscription);
    const licenses = await all(subscriptions.map((x) => api<Api.License[]>('GET', `/api/v1/subscriptions/${x.id}/licenses`).catch(() => [])));
    return { subscriptions, assignments: licenses.flat().map(toLicense) };
  },
  assets: loadAssets,
  findings: async () => ({ findings: (await api<Api.Finding[]>('GET', '/api/v1/findings')).map(toFinding) }),
  imports: async () => ({ imports: (await api<Api.ImportJob[]>('GET', '/api/v1/imports')).map(toImport) }),
  notifications: async () => ({ notifications: ((await api<Api.NotificationPage>('GET', '/api/v1/notifications')).items ?? []).map(toNotification) }),
  costHistory: async () => {
    const sum = await api<Api.Summary>('GET', '/api/v1/analytics/summary', undefined, { months: 12 });
    return { costHistory: (sum.agentSpend ?? []).map((m) => ({ month: s(m.month), agents: n(m.amount), software: 0 })) };
  },
  connections: async () => {
    const connections = (await api<Api.Connection[]>('GET', '/api/v1/connections')).map(toConnection);
    const runs = await all(connections.map((c) => api<Api.ConnectionRun[]>('GET', `/api/v1/connections/${c.id}/runs`).then((rs) => rs.map((r) => toRun(c.id, r))).catch(() => [])));
    return { connections, connectionRuns: runs.flat() };
  },
  systemTypes: async () => ({ systemTypes: (await api<Api.SystemType[]>('GET', '/api/v1/connections/types')).map(toSystemType) }),
};

// Разделы, которые затрагивает каждое действие (ядро могло поменять и соседние: находки, уведомления)
export const AFTER = {
  agents: ['agents', 'budgets', 'notifications'],
  mandates: ['mandates', 'notifications'],
  budgets: ['budgets'],
  approvals: ['approvals', 'operations', 'budgets', 'notifications'],
  software: ['vendors', 'contracts', 'subscriptions', 'findings'],
  assets: ['assets', 'findings', 'notifications'],
  findings: ['findings', 'notifications', 'costHistory'],
  imports: ['imports', 'notifications'],
  org: ['departments', 'employees', 'users', 'findings', 'notifications'],
  connections: ['connections', 'notifications'],
  notifications: ['notifications'],
} satisfies Record<string, Slice[]>;

export type { ApiAsset, ApiAssignment };

// ——— Запросы действий ———

export const remote = {
  agentDetails: (a: Pick<Agent, 'name' | 'description' | 'framework' | 'departmentId' | 'ownerId' | 'mandateId'>): Api.AgentDetails =>
    ({ name: a.name, description: a.description, framework: a.framework, departmentId: a.departmentId, ownerId: a.ownerId, mandateId: a.mandateId }),

  async createAgent(details: Api.AgentDetails, limit: number, st: AppState, currency: string): Promise<string> {
    const agent = await api<Api.Agent>('POST', '/api/v1/agents', details);
    // Бюджет агента висит под бюджетом отдела, тот — под бюджетом компании; недостающие уровни создаются
    let company = st.budgets.find((b) => b.level === 'company');
    if (!company) company = toBudget(await api<Api.Budget>('POST', '/api/v1/budgets', { name: 'Компания', level: 'company', limit, currency }));
    let dept = st.budgets.find((b) => b.level === 'department' && b.refId === details.departmentId);
    if (!dept) {
      const name = st.departments.find((d) => d.id === details.departmentId)?.name ?? 'Отдел';
      dept = toBudget(await api<Api.Budget>('POST', '/api/v1/budgets', { name, level: 'department', refId: details.departmentId, parentId: company.id, limit, currency }));
    }
    await api('POST', '/api/v1/budgets', { name: details.name, level: 'agent', refId: agent.id, parentId: dept.id, limit, currency });
    return s(agent.id);
  },

  async uploadImport(file: File, vendorId?: string): Promise<string> {
    const job = await upload<Api.ImportJob>('/api/v1/imports', file, { vendorId });
    return s(job.id);
  },

  async receipt(operationId: string) {
    return api<Api.ReceiptView>('GET', `/api/v1/operations/${operationId}/receipt`);
  },

  async assetHistory(assetId: string): Promise<AssetAssignment[]> {
    return (await api<ApiAssignment[]>('GET', `/api/v1/assets/${assetId}/assignments`)).map(toAssignment);
  },
};
