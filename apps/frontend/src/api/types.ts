// Типы повторяют модель данных с доски Miro (раздел «Модель данных»).
// Когда в schemas/openapi появятся core.yaml и gateway.yaml, эти типы заменит сгенерированный клиент.

export type Role = 'owner' | 'finance' | 'it_admin' | 'agent_owner' | 'auditor' | 'employee';
/** Роли персонала: им видны данные компании. employee — только «Мой кабинет» (ADR 0051). */
export const STAFF_ROLES: Role[] = ['owner', 'finance', 'it_admin', 'agent_owner', 'auditor'];

export interface User {
  id: string;
  name: string;
  email: string;
  role: Role;
  employeeId?: string;
}

export interface Department {
  id: string;
  name: string;
  /** Руководитель согласует отпуска отдела (ADR 0053) */
  headEmployeeId?: string | null;
}

export interface Employee {
  id: string;
  name: string;
  email: string;
  departmentId: string;
  position: string;
  active: boolean;
}

export type AgentStatus = 'active' | 'suspended';

export interface Agent {
  id: string;
  name: string;
  description: string;
  framework: string;
  departmentId: string;
  ownerId: string; // employee
  status: AgentStatus;
  riskScore: number; // 0–100
  riskReason: string;
  mandateId: string;
  createdAt: string;
}

export interface VirtualKey {
  id: string;
  agentId: string;
  prefix: string;
  createdAt: string;
  status: 'active' | 'revoked';
}

export interface Mandate {
  id: string;
  name: string;
  models: string[];
  vendors: string[];
  maxPerOperation: number;
  approvalThreshold: number;
  hours: string;
  status: 'draft' | 'published';
  version: number;
  updatedAt: string;
}

export type BudgetLevel = 'company' | 'department' | 'agent';

export interface Budget {
  id: string;
  name: string;
  level: BudgetLevel;
  refId?: string; // department или agent
  parentId?: string;
  limit: number;
  spent: number;
  reserved: number;
}

export type GatewayCode =
  | 'allowed'
  | '401_unauthorized'
  | '402_budget_exceeded'
  | '403_policy_denied'
  | '409_duplicate_operation'
  | '423_agent_suspended'
  | '429_rate_limited'
  | 'approval_rejected'
  | 'pending_approval';

export interface Receipt {
  hash: string;
  signature: string;
  prevHash: string;
  status: 'pending' | 'anchored';
  batchId?: string;
  txHash?: string;
  merklePath?: string[];
}

export interface Operation {
  id: string;
  agentId: string;
  at: string;
  vendor: string;
  resource: string;
  cost: number;
  code: GatewayCode;
  risk: number;
  failedStep?: number; // ступень лестницы проверок, на которой отказ (1–7)
  receipt?: Receipt;
}

export interface Approval {
  id: string;
  agentId: string;
  description: string;
  amount: number;
  reason: string;
  createdAt: string;
  deadline: string;
  status: 'pending' | 'approved' | 'rejected' | 'expired';
  decidedBy?: string;
  comment?: string;
}

export interface Vendor {
  id: string;
  name: string;
  inn: string;
  category: string;
}

export interface Contract {
  id: string;
  vendorId: string;
  number: string;
  startDate: string;
  endDate: string;
  noticeDays: number;
  autoRenew: boolean;
  amount: number;
}

export interface Subscription {
  id: string;
  vendorId: string;
  product: string;
  contractId?: string;
  departmentId: string;
  seats: number;
  pricePerSeat: number; // ₽ в месяц
  billingPeriod: 'month' | 'year';
  renewalDate: string;
  plan?: string; // тариф по каталогу тарифов ядра: по нему аудит ищет избыточный тариф и годовую оплату
}

// Техника сотрудников (кадр 15 доски). Повторяет модуль assets ядра: Asset и Assignment из schemas/openapi/core.yaml.
export type AssetType = 'LAPTOP' | 'PHONE' | 'MONITOR' | 'OTHER';
export type AssetStatus = 'IN_STOCK' | 'ASSIGNED' | 'RETIRED';
export type AssetSource = 'manual' | 'jamf' | 'intune' | 'onec';

export interface Asset {
  id: string;
  inventoryNumber: string;
  model: string;
  type: AssetType;
  cost: number; // ₽, из учётной системы
  serialNumber?: string;
  status: AssetStatus;
  source: AssetSource; // откуда пришла запись: вручную или от коннектора
  createdAt: string;
}

// Одна выдача: кто держит технику и до какого срока. У каждой выдачи есть акт приёма-передачи (PDF).
export interface AssetAssignment {
  id: string;
  assetId: string;
  employeeId: string;
  issuedAt: string;
  returnDue: string | null; // null — бессрочно, до увольнения
  returnedAt: string | null;
  actNumber: string | null; // АВ-2026-0001; null у выдач, записанных до появления актов
  issuedBy: string; // имя выдавшего или connector:<источник>
}

export interface LicenseAssignment {
  id: string;
  subscriptionId: string;
  employeeId: string;
  lastActive: string | null;
}

export type FindingStatus =
  | 'new'
  | 'review'
  | 'accepted'
  | 'in_progress'
  | 'confirmed'
  | 'rejected'
  | 'postponed'
  | 'auto_closed';

export interface FindingEvent {
  at: string;
  by: string;
  text: string;
}

export interface Finding {
  id: string;
  rule: string;
  title: string;
  description: string;
  subscriptionId?: string;
  agentId?: string;
  assetId?: string;
  employeeId?: string; // у находок по технике: чья это техника
  expectedSavings: number; // ₽ в месяц; у находок по технике — разово (стоимость техники)
  status: FindingStatus;
  ownerId?: string; // user
  postponedUntil?: string;
  rejectReason?: string;
  missedInvoices: number;
  createdAt: string;
  history: FindingEvent[];
}

export type ImportType = 'excel' | 'csv' | 'parquet' | 'pdf' | 'scan' | 'bank';
export type ImportStatus = 'uploaded' | 'mapping' | 'review' | 'done' | 'failed';

export interface ReviewTask {
  id: string;
  row: number;
  field: string;
  value: string;
  confidence: number;
  resolved: boolean;
}

export interface ImportJob {
  id: string;
  fileName: string;
  type: ImportType;
  vendorId?: string;
  uploadedAt: string;
  uploadedBy: string;
  status: ImportStatus;
  rows: number;
  total: number;
  columns: string[];
  mapping: Record<string, string>; // колонка файла → поле FOCUS
  errors: { row: number; message: string }[];
  reviewTasks: ReviewTask[];
  templateSaved: boolean;
  failure?: string; // почему разбор не удался (от ingest)
}

export interface Notification {
  id: string;
  at: string;
  text: string;
  link?: string;
  level: 'info' | 'warning' | 'critical' | 'good';
  read: boolean;
}

export interface Report {
  id: string;
  name: string;
  format: 'xlsx' | 'pdf';
  createdAt: string;
  period: string;
  createdBy: string;
}

export interface CostPoint {
  month: string; // YYYY-MM
  software: number;
  agents: number;
}

// Подключённые системы (ADR 0019). Типы берутся из клиента, сгенерированного по schemas/openapi/core.yaml
// (tools/codegen → generated/core.ts); здесь только обязательные поля, которые ядро всегда присылает.
import type { Connection as ApiConnection, ConnectionRun as ApiConnectionRun, SystemType as ApiSystemType } from './generated/core';
export type ConnectionStatus = NonNullable<ApiConnection['status']>;
export type Connection = Required<Pick<ApiConnection, 'id' | 'type' | 'name' | 'status' | 'enabled' | 'managedInCore' | 'settings' | 'recordsTotal' | 'createdAt'>>
  & Pick<ApiConnection, 'lastSyncAt' | 'recordsLastSync' | 'lastError' | 'lastErrorAt' | 'syncRequestedAt'>;
export type ConnectionRun = Required<Pick<ApiConnectionRun, 'id' | 'startedAt' | 'status' | 'records'>>
  & Pick<ApiConnectionRun, 'finishedAt' | 'error'> & { connectionId: string };
export type SystemType = Required<ApiSystemType>;

export interface AppState {
  users: User[];
  departments: Department[];
  employees: Employee[];
  agents: Agent[];
  keys: VirtualKey[];
  mandates: Mandate[];
  budgets: Budget[];
  operations: Operation[];
  approvals: Approval[];
  vendors: Vendor[];
  contracts: Contract[];
  subscriptions: Subscription[];
  assignments: LicenseAssignment[];
  assets: Asset[];
  assetAssignments: AssetAssignment[];
  findings: Finding[];
  imports: ImportJob[];
  notifications: Notification[];
  reports: Report[];
  costHistory: CostPoint[];
  connections: Connection[];
  connectionRuns: ConnectionRun[];
}

/** GET /api/v1/me: кто вошёл. employee пуст, если человек не сотрудник компании (ADR 0051). */
export interface Me {
  userId: string | null;
  name: string;
  email: string;
  roles: string[];
  employee: { id: string; name: string; position: string; department: string; email: string } | null;
}
