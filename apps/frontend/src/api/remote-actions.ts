// Действия кнопок поверх REST API ядра. Сигнатуры как у демо-хранилища (store.tsx), чтобы страницы не знали,
// откуда данные. Ошибка запроса показывается всплывающим сообщением, действие возвращает null/undefined.
import { focusIds } from './focus';
import type * as Api from './generated/core';
import { ApiError, api, download } from './http';
import { AFTER, remote, type Slice } from './remote';
import type { Agent, AppState, AssetAssignment, Contract, Employee, Mandate, Notification, Role, Subscription, User, Vendor } from './types';
import type { FindingAction } from './store';
import { fmtMoney } from '../lib';

type Toast = (text: string, level?: Notification['level']) => void;

export interface RemoteContext {
  getState: () => AppState;
  currency: () => string;
  reload: (slices: Slice[]) => Promise<void>;
  patch: (fn: (s: AppState) => void) => void;
  toast: Toast;
}

export function remoteActions({ getState, currency, reload, patch, toast }: RemoteContext) {
  /** Запрос, затем перечитать разделы и показать сообщение. Ошибка — сообщение ядра. */
  async function run<T>(slices: Slice[], call: () => Promise<T>, ok?: string | ((r: T) => string), level: Notification['level'] = 'good'): Promise<T | null> {
    try {
      const r = await call();
      await reload(slices);
      if (ok) toast(typeof ok === 'string' ? ok : ok(r), level);
      return r;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Нет связи с сервером', 'critical');
      return null;
    }
  }

  const keyAgent = (keyId: string) => getState().keys.find((k) => k.id === keyId)?.agentId;
  const openAssignment = (assetId: string) => getState().assetAssignments.find((a) => a.assetId === assetId && !a.returnedAt);
  const contractBody = (c: Omit<Contract, 'id'>): Api.ContractDetails =>
    ({ vendorId: c.vendorId, number: c.number, startDate: c.startDate, endDate: c.endDate, noticeDays: c.noticeDays, autoRenew: c.autoRenew, amount: c.amount });
  const subscriptionBody = (x: Omit<Subscription, 'id'>): Api.SubscriptionDetails => ({
    vendorId: x.vendorId, product: x.product, contractId: x.contractId || undefined, departmentId: x.departmentId, seats: x.seats,
    pricePerSeat: x.pricePerSeat, billingPeriod: x.billingPeriod, renewalDate: x.renewalDate, plan: x.plan?.trim() || undefined,
  });

  return {
    async reset() {
      await reload(['company', 'users', 'departments', 'employees', 'agents', 'mandates', 'budgets', 'operations', 'approvals', 'vendors',
        'contracts', 'subscriptions', 'assets', 'findings', 'imports', 'notifications', 'costHistory', 'connections', 'systemTypes']);
      toast('Данные обновлены', 'good');
    },

    // ——— Уведомления ———
    async markRead(id: string) {
      patch((s) => { const x = s.notifications.find((y) => y.id === id); if (x) x.read = true; });
      await run(AFTER.notifications, () => api('PUT', `/api/v1/notifications/${id}/read`));
    },
    async markAllRead() {
      patch((s) => s.notifications.forEach((x) => (x.read = true)));
      await run(AFTER.notifications, () => api('PUT', '/api/v1/notifications/read'));
    },
    async clearNotifications() {
      await run(AFTER.notifications, () => api('DELETE', '/api/v1/notifications'), 'Уведомления очищены', 'info');
    },

    // ——— Агенты ———
    async createAgent(data: Pick<Agent, 'name' | 'description' | 'framework' | 'departmentId' | 'ownerId' | 'mandateId'>, limit: number) {
      return run(AFTER.agents, () => remote.createAgent(remote.agentDetails(data), limit, getState(), currency()), `Агент «${data.name}» создан`);
    },
    async updateAgent(id: string, p: Partial<Agent>) {
      const a = getState().agents.find((x) => x.id === id);
      if (!a) return;
      await run(AFTER.agents, () => api('PUT', `/api/v1/agents/${id}`, remote.agentDetails({ ...a, ...p })), 'Изменения сохранены');
    },
    async suspendAgent(id: string, _by: string) {
      await run(AFTER.agents, () => api('PUT', `/api/v1/agents/${id}/suspension`), 'Агент остановлен. Шлюз отвечает 423 agent_suspended', 'warning');
    },
    async resumeAgent(id: string) {
      await run(AFTER.agents, () => api('DELETE', `/api/v1/agents/${id}/suspension`), 'Агент снова работает');
    },
    async deleteAgent(id: string) {
      await run(AFTER.agents, () => api('DELETE', `/api/v1/agents/${id}`), 'Агент удалён, его ключи отозваны', 'info');
    },
    async issueKey(agentId: string): Promise<string> {
      const r = await run(AFTER.agents, () => api<Api.IssuedKey>('POST', `/api/v1/agents/${agentId}/keys`), 'Ключ выпущен. Скопируйте его: больше он не будет показан');
      return r?.secret ?? '';
    },
    async revokeKey(keyId: string) {
      const agentId = keyAgent(keyId);
      if (!agentId) return;
      await run(AFTER.agents, () => api('DELETE', `/api/v1/agents/${agentId}/keys/${keyId}`), 'Ключ отозван. Запросы с ним получат 401', 'warning');
    },

    // ——— Мандаты ———
    async saveMandate(m: Omit<Mandate, 'id' | 'version' | 'status' | 'updatedAt'> & { id?: string }) {
      const body: Api.MandateDetails = { name: m.name, models: m.models, vendors: m.vendors, maxPerOperation: m.maxPerOperation, approvalThreshold: m.approvalThreshold, hours: m.hours };
      const r = await run(AFTER.mandates, () => m.id ? api<Api.Mandate>('PUT', `/api/v1/mandates/${m.id}`, body) : api<Api.Mandate>('POST', '/api/v1/mandates', body),
        'Черновик мандата сохранён. Опубликуйте его, чтобы шлюз применил правила', 'info');
      return r?.id ?? '';
    },
    async publishMandate(id: string) {
      await run(AFTER.mandates, () => api('PUT', `/api/v1/mandates/${id}/publication`), 'Мандат опубликован: снимок правил отправлен в шлюз');
    },
    async deleteMandate(id: string) {
      await run(AFTER.mandates, () => api('DELETE', `/api/v1/mandates/${id}`), 'Черновик удалён', 'info');
    },

    // ——— Бюджеты и согласования ———
    async setBudgetLimit(id: string, limit: number) {
      await run(AFTER.budgets, () => api('PUT', `/api/v1/budgets/${id}/limit`, { limit }), `Лимит обновлён: ${fmtMoney(limit)}`);
    },
    async decideApproval(id: string, approve: boolean, _by: string, comment: string) {
      await run(AFTER.approvals, () => api('PUT', `/api/v1/approvals/${id}/decision`, { verdict: approve ? 'approve' : 'reject', comment: comment || undefined }),
        approve ? 'Операция одобрена: агент может повторить запрос' : 'Операция отклонена, резерв возвращён', approve ? 'good' : 'warning');
    },
    anchorPending() {
      return 0; // пакеты закрепляет нотариус по расписанию
    },

    // ——— ПО ———
    async createVendor(v: Omit<Vendor, 'id'>) {
      const r = await run(AFTER.software, () => api<Api.Vendor>('POST', '/api/v1/vendors', v), `Поставщик «${v.name}» добавлен`);
      return r?.id ?? '';
    },
    async saveContract(c: Omit<Contract, 'id'> & { id?: string }) {
      await run(AFTER.software, () => c.id ? api('PUT', `/api/v1/contracts/${c.id}`, contractBody(c)) : api('POST', '/api/v1/contracts', contractBody(c)), 'Договор сохранён');
    },
    async toggleAutoRenew(id: string) {
      const c = getState().contracts.find((x) => x.id === id);
      if (!c) return;
      await run(AFTER.software, () => api('PUT', `/api/v1/contracts/${id}`, contractBody({ ...c, autoRenew: !c.autoRenew })), 'Настройка автопродления изменена', 'info');
    },
    async saveSubscription(sub: Omit<Subscription, 'id'> & { id?: string }) {
      const r = await run(AFTER.software, () => sub.id ? api<Api.Subscription>('PUT', `/api/v1/subscriptions/${sub.id}`, subscriptionBody(sub))
        : api<Api.Subscription>('POST', '/api/v1/subscriptions', subscriptionBody(sub)), 'Подписка сохранена');
      return r?.id ?? '';
    },
    async revokeLicense(assignmentId: string) {
      await run(AFTER.software, () => api('DELETE', `/api/v1/licenses/${assignmentId}`), 'Лицензия снята. Место освободилось');
    },
    async assignLicense(subscriptionId: string, employeeId: string) {
      await run(AFTER.software, () => api('POST', `/api/v1/subscriptions/${subscriptionId}/licenses`, { employeeId }), 'Лицензия назначена');
    },

    // ——— Техника ———
    async createAsset(a: { inventoryNumber: string; model: string; type: string; cost: number; serialNumber?: string }): Promise<string | null> {
      const r = await run(AFTER.assets, () => api<{ id: string }>('POST', '/api/v1/assets', { ...a, inventoryNumber: a.inventoryNumber.trim(), model: a.model.trim(), serialNumber: a.serialNumber?.trim() || undefined }),
        `Техника ${a.inventoryNumber} поставлена на учёт`);
      return r?.id ?? null;
    },
    async issueAsset(assetId: string, employeeId: string, returnDue: string | null, by: string): Promise<AssetAssignment | null> {
      const r = await run(AFTER.assets, () => api<{ id: string; actNumber: string | null; issuedAt: string; returnDue: string | null }>('PUT', `/api/v1/assets/${assetId}/assignment`, { employeeId, returnDue: returnDue ?? undefined }),
        (x) => x.actNumber ? `Техника выдана. Сформирован акт ${x.actNumber}` : 'Техника выдана');
      return r ? { id: r.id, assetId, employeeId, issuedAt: r.issuedAt, returnDue: r.returnDue ?? null, returnedAt: null, actNumber: r.actNumber, issuedBy: by } : null;
    },
    async moveReturnDate(assetId: string, returnDue: string | null) {
      const o = openAssignment(assetId);
      if (!o) return;
      await run(AFTER.assets, () => api('PUT', `/api/v1/assets/${assetId}/assignment`, { employeeId: o.employeeId, returnDue: returnDue ?? undefined }),
        returnDue ? 'Срок возврата изменён. Акт остаётся прежним' : 'Срок возврата снят. Акт остаётся прежним');
    },
    async returnAsset(assetId: string) {
      await run(AFTER.assets, () => api('DELETE', `/api/v1/assets/${assetId}/assignment`), 'Техника принята на склад');
    },
    async retireAsset(assetId: string) {
      await run(AFTER.assets, () => api('PUT', `/api/v1/assets/${assetId}/retirement`), 'Техника списана. История выдач сохранена', 'info');
    },
    /** История выдач одной единицы: список техники приносит только текущую выдачу. */
    async loadAssetHistory(assetId: string) {
      try {
        const history = await remote.assetHistory(assetId);
        patch((s) => { s.assetAssignments = [...s.assetAssignments.filter((a) => a.assetId !== assetId), ...history]; });
      } catch { /* покажем, что есть */ }
    },
    async downloadAct(assetId: string, assignmentId: string, actNumber: string) {
      try { await download(`/api/v1/assets/${assetId}/assignments/${assignmentId}/act`, `${actNumber}.pdf`); }
      catch (e) { toast(e instanceof ApiError ? e.message : 'Не удалось скачать акт', 'critical'); }
    },

    // ——— Находки ———
    async findingAction(id: string, action: FindingAction, _by: string, payload?: { ownerId?: string; date?: string; reason?: string }) {
      // Ядро ждёт дату без времени (LocalDate)
      const body = { action, ownerId: payload?.ownerId || undefined, date: payload?.date ? payload.date.slice(0, 10) : undefined, reason: payload?.reason || undefined };
      await run(AFTER.findings, () => api('POST', `/api/v1/findings/${id}/actions`, body), 'Готово');
    },

    // ——— Импорт ———
    async createImport(file: File, _by: string, vendorId?: string): Promise<string> {
      const r = await run(AFTER.imports, () => remote.uploadImport(file, vendorId), 'Файл загружен. Начат разбор', 'info');
      return r ?? '';
    },
    async confirmMapping(id: string, mapping: Record<string, string>, saveTemplate: boolean) {
      await run(AFTER.imports, () => api('PUT', `/api/v1/imports/${id}/mapping`, { mapping: focusIds(mapping), saveTemplate }),
        saveTemplate ? 'Колонки сопоставлены, шаблон поставщика сохранён' : 'Колонки сопоставлены');
    },
    async resolveTask(importId: string, taskId: string, value: string) {
      await run(AFTER.imports, () => api('PUT', `/api/v1/imports/${importId}/review-tasks/${taskId}`, { value }), 'Исправление сохранено как пример поставщика');
    },
    retryImport(_id: string) { /* только в демо */ },
    deleteImport(_id: string) { /* только в демо */ },

    // ——— Оргструктура ———
    async createDepartment(name: string) {
      await run(AFTER.org, () => api('POST', '/api/v1/departments', { name }), `Отдел «${name}» создан`);
    },
    async saveEmployee(e: Omit<Employee, 'id' | 'active'> & { id?: string }) {
      const body: Api.EmployeeDetails = { name: e.name, email: e.email, departmentId: e.departmentId, position: e.position };
      await run(AFTER.org, () => e.id ? api('PUT', `/api/v1/employees/${e.id}`, body) : api('POST', '/api/v1/employees', body), 'Сотрудник сохранён');
    },
    async setEmployeeActive(id: string, active: boolean) {
      if (active) return; // увольнение в ядре не отменяется
      await run([...AFTER.org, 'assets', 'subscriptions'], () => api('PUT', `/api/v1/employees/${id}/dismissal`),
        'Сотрудник отмечен уволенным. Движок аудита проверит его лицензии и технику', 'warning');
    },
    async inviteUser(u: Omit<User, 'id'>) {
      await run(AFTER.org, () => api('POST', '/api/v1/users', { name: u.name, email: u.email, role: u.role, employeeId: u.employeeId || undefined }), `Пользователь ${u.email} добавлен`);
    },
    async setUserRole(id: string, role: Role) {
      await run(AFTER.org, () => api('PUT', `/api/v1/users/${id}/role`, { role }), 'Роль изменена');
    },
    async removeUser(id: string) {
      await run(AFTER.org, () => api('DELETE', `/api/v1/users/${id}`), 'Доступ пользователя закрыт', 'info');
    },

    // ——— Отчёты: собираются в браузере из загруженных данных и живут до закрытия вкладки ———
    createReport(name: string, format: 'xlsx' | 'pdf', period: string, by: string) {
      const id = `r-${Date.now()}`;
      patch((s) => { s.reports.unshift({ id, name, format, period, createdAt: new Date().toISOString(), createdBy: by }); });
      toast('Отчёт сформирован', 'good');
      return id;
    },
    deleteReport(id: string) {
      patch((s) => { s.reports = s.reports.filter((r) => r.id !== id); });
    },

    // ——— Подключённые системы ———
    async connectSystem(type: string, name: string, settings: Record<string, string>, secrets?: Record<string, string>): Promise<string | null> {
      const clean = Object.fromEntries(Object.entries(settings).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v));
      const hidden = secrets && Object.fromEntries(Object.entries(secrets).filter(([, v]) => v.trim()));
      const r = await run(AFTER.connections, () => api<Api.Connection>('POST', '/api/v1/connections', { type, name: name.trim(), settings: clean, secrets: hidden && Object.keys(hidden).length ? hidden : undefined }),
        'Система подключена. Агент коннекторов начнёт выгрузку');
      return r?.id ?? null;
    },
    async disconnectSystem(id: string) {
      await run(AFTER.connections, () => api('DELETE', `/api/v1/connections/${id}`), 'Система отключена. История выгрузок сохранена', 'info');
    },
    async syncSystem(id: string) {
      await run(AFTER.connections, () => api('POST', `/api/v1/connections/${id}/sync`), 'Синхронизация запрошена', 'info');
    },

    // ——— Операции ———
    async loadReceipt(operationId: string) {
      try {
        const r = await remote.receipt(operationId);
        patch((s) => {
          const o = s.operations.find((x) => x.id === operationId);
          if (o) o.receipt = { hash: r.hash ?? '', signature: r.signature ?? '', prevHash: r.previousHash ?? '', status: r.status ?? 'pending', batchId: r.batchId, txHash: r.txHash, merklePath: r.merklePath };
        });
      } catch { /* квитанции ещё нет */ }
    },

    async verifyChain(): Promise<Api.ChainVerification | null> {
      try { return await api<Api.ChainVerification>('GET', '/api/v1/journal/verification'); }
      catch (e) { toast(e instanceof ApiError ? e.message : 'Нет связи с сервером', 'critical'); return null; }
    },

    simulateTick() { /* живые события приходят по SSE */ },
  };
}

export type RemoteActions = ReturnType<typeof remoteActions>;
