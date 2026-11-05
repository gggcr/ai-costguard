// Хранилище прототипа: состояние в памяти + localStorage, все действия кнопок.
// Каждое действие здесь соответствует будущему вызову REST API ядра или шлюза.
// Когда появится клиент из schemas/openapi, тела функций заменятся на запросы, интерфейс останется.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type {
  Agent, AppState, Approval, Budget, Contract, Employee, Finding, FindingStatus, GatewayCode, ImportJob,
  ImportType, Mandate, Notification, Operation, Role, Subscription, User, Vendor,
} from './types';
import { createSeed } from './mock-data';
import { fakeHash, fmtRub, newId } from '../lib';

const STORAGE_KEY = 'costguard-demo-state-v1';

function loadState(): AppState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as AppState;
  } catch {
    /* нет доступа к localStorage — работаем в памяти */
  }
  return createSeed();
}

export interface Toast {
  id: string;
  text: string;
  level: Notification['level'];
}

export const FOCUS_FIELDS = ['Поставщик', 'Продукт', 'Сотрудник', 'Email', 'Сумма', 'Валюта', 'Период начала', 'Период окончания', 'Последняя активность', 'Отдел', '— не импортировать —'];

export const FINDING_ACTIONS = {
  assign: 'Назначить владельца',
  accept: 'Принять',
  postpone: 'Отложить до даты',
  reject: 'Отклонить',
  createTask: 'Создать задачу',
  confirm: 'Экономия видна в счёте',
  missInvoice: 'Экономии нет в счёте',
  autoClose: 'Условие исчезло',
  reopen: 'Условие вернулось',
  dateReached: 'Наступила дата',
} as const;
export type FindingAction = keyof typeof FINDING_ACTIONS;

// Разрешённые переходы — по схеме «Жизненный цикл находки»
export const FINDING_TRANSITIONS: Record<FindingStatus, FindingAction[]> = {
  new: ['assign', 'autoClose'],
  review: ['accept', 'postpone', 'reject', 'autoClose'],
  accepted: ['createTask'],
  in_progress: ['confirm', 'missInvoice'],
  postponed: ['dateReached'],
  auto_closed: ['reopen'],
  rejected: [],
  confirmed: [],
};

function useActions(
  setState: (fn: (s: AppState) => void) => void,
  pushToast: (t: string, l?: Notification['level']) => void,
  getState: () => AppState,
) {
  return useMemo(() => {
    const notify = (s: AppState, text: string, level: Notification['level'], link?: string) => {
      s.notifications.unshift({ id: newId('n'), at: new Date().toISOString(), text, level, link, read: false });
    };

    // Списать сумму на бюджет агента и все родительские уровни
    const charge = (s: AppState, agentId: string, amount: number) => {
      let b: Budget | undefined = s.budgets.find((x) => x.level === 'agent' && x.refId === agentId);
      while (b) {
        b.spent = Math.round((b.spent + amount) * 100) / 100;
        b = b.parentId ? s.budgets.find((x) => x.id === b!.parentId) : undefined;
      }
    };

    const budgetLeft = (s: AppState, agentId: string, amount: number) => {
      let b: Budget | undefined = s.budgets.find((x) => x.level === 'agent' && x.refId === agentId);
      while (b) {
        if (b.spent + b.reserved + amount > b.limit) return b;
        b = b.parentId ? s.budgets.find((x) => x.id === b!.parentId) : undefined;
      }
      return null;
    };

    const addOperation = (s: AppState, agentId: string, resource: string, vendor: string, cost: number, code: GatewayCode, risk: number, failedStep?: number) => {
      const id = newId('op');
      const op: Operation = {
        id, agentId, at: new Date().toISOString(), vendor, resource, cost: code === 'allowed' ? cost : 0, code, risk, failedStep,
        receipt: code === 'allowed' ? { hash: fakeHash(id), signature: fakeHash(id + 'sig', 128), prevHash: s.operations[0]?.receipt?.hash ?? fakeHash('genesis'), status: 'pending' } : undefined,
      };
      s.operations.unshift(op);
      if (code === 'allowed') charge(s, agentId, cost);
      return op;
    };

    const agentName = (s: AppState, id?: string) => s.agents.find((a) => a.id === id)?.name ?? '—';

    return {
      reset() {
        setState((s) => Object.assign(s, createSeed()));
        pushToast('Демо-данные восстановлены', 'good');
      },

      // ——— Уведомления ———
      markRead(id: string) {
        setState((s) => { const n = s.notifications.find((x) => x.id === id); if (n) n.read = true; });
      },
      markAllRead() {
        setState((s) => s.notifications.forEach((n) => (n.read = true)));
      },
      clearNotifications() {
        setState((s) => { s.notifications = []; });
        pushToast('Уведомления очищены');
      },

      // ——— Агенты ———
      createAgent(data: Pick<Agent, 'name' | 'description' | 'framework' | 'departmentId' | 'ownerId' | 'mandateId'>, limit: number) {
        const id = newId('a');
        setState((s) => {
          s.agents.unshift({ ...data, id, status: 'active', riskScore: 0, riskReason: 'Нет данных: агент только подключён', createdAt: new Date().toISOString() });
          s.budgets.push({ id: `b-${id}`, name: data.name, level: 'agent', refId: id, parentId: `b-${data.departmentId}`, limit, spent: 0, reserved: 0 });
          if (!s.budgets.some((b) => b.id === `b-${data.departmentId}`)) {
            const d = s.departments.find((x) => x.id === data.departmentId);
            s.budgets.push({ id: `b-${data.departmentId}`, name: d?.name ?? 'Отдел', level: 'department', refId: data.departmentId, parentId: 'b-company', limit: limit + 20000, spent: 0, reserved: 0 });
          }
          notify(s, `Подключён новый агент «${data.name}»`, 'info', `/agents/${id}`);
        });
        pushToast(`Агент «${data.name}» создан`, 'good');
        return id;
      },
      updateAgent(id: string, patch: Partial<Agent>) {
        setState((s) => { const a = s.agents.find((x) => x.id === id); if (a) Object.assign(a, patch); });
        pushToast('Изменения сохранены', 'good');
      },
      suspendAgent(id: string, by: string) {
        setState((s) => {
          const a = s.agents.find((x) => x.id === id);
          if (!a) return;
          a.status = 'suspended';
          a.riskReason = `Остановлен вручную: ${by}`;
          notify(s, `Агент «${a.name}» остановлен (${by})`, 'warning', `/agents/${id}`);
        });
        pushToast('Агент остановлен. Шлюз отвечает 423 agent_suspended', 'warning');
      },
      resumeAgent(id: string) {
        setState((s) => {
          const a = s.agents.find((x) => x.id === id);
          if (!a) return;
          a.status = 'active';
          a.riskScore = Math.min(a.riskScore, 30);
          a.riskReason = 'Запущен повторно, риск пересчитывается';
        });
        pushToast('Агент снова работает', 'good');
      },
      deleteAgent(id: string) {
        setState((s) => {
          s.agents = s.agents.filter((a) => a.id !== id);
          s.keys.forEach((k) => { if (k.agentId === id) k.status = 'revoked'; });
          s.budgets = s.budgets.filter((b) => !(b.level === 'agent' && b.refId === id));
        });
        pushToast('Агент удалён, его ключи отозваны');
      },
      issueKey(agentId: string): string {
        const secret = `cg_live_${fakeHash(newId('key'), 6)}_${fakeHash(String(Math.random()), 32)}`;
        setState((s) => { s.keys.unshift({ id: newId('k'), agentId, prefix: secret.slice(0, 14), createdAt: new Date().toISOString(), status: 'active' }); });
        pushToast('Ключ выпущен. Скопируйте его: больше он не будет показан', 'good');
        return secret;
      },
      revokeKey(keyId: string) {
        setState((s) => { const k = s.keys.find((x) => x.id === keyId); if (k) k.status = 'revoked'; });
        pushToast('Ключ отозван. Запросы с ним получат 401', 'warning');
      },

      // ——— Мандаты и правила ———
      saveMandate(m: Omit<Mandate, 'id' | 'version' | 'status' | 'updatedAt'> & { id?: string }) {
        const id = m.id ?? newId('m');
        setState((s) => {
          const existing = s.mandates.find((x) => x.id === id);
          if (existing) {
            Object.assign(existing, m, { status: 'draft', updatedAt: new Date().toISOString() });
          } else {
            s.mandates.push({ ...m, id, version: 0, status: 'draft', updatedAt: new Date().toISOString() });
          }
        });
        pushToast('Черновик мандата сохранён. Опубликуйте его, чтобы шлюз применил правила', 'info');
        return id;
      },
      publishMandate(id: string) {
        setState((s) => {
          const m = s.mandates.find((x) => x.id === id);
          if (!m) return;
          m.status = 'published';
          m.version += 1;
          m.updatedAt = new Date().toISOString();
          notify(s, `Опубликован мандат «${m.name}», версия ${m.version}`, 'info', '/policies');
        });
        pushToast('Мандат опубликован: снимок правил отправлен в шлюз (policy.snapshots.v1)', 'good');
      },
      deleteMandate(id: string) {
        setState((s) => { s.mandates = s.mandates.filter((m) => m.id !== id); });
        pushToast('Черновик удалён');
      },

      // ——— Бюджеты ———
      setBudgetLimit(id: string, limit: number) {
        setState((s) => { const b = s.budgets.find((x) => x.id === id); if (b) b.limit = limit; });
        pushToast(`Лимит обновлён: ${fmtRub(limit)}`, 'good');
      },

      // ——— Согласования ———
      decideApproval(id: string, approve: boolean, by: string, comment: string) {
        setState((s) => {
          const ap = s.approvals.find((x) => x.id === id);
          if (!ap || ap.status !== 'pending') return;
          ap.status = approve ? 'approved' : 'rejected';
          ap.decidedBy = by;
          ap.comment = comment;
          const a = s.agents.find((x) => x.id === ap.agentId);
          if (approve) {
            addOperation(s, ap.agentId, ap.description, 'По согласованию', ap.amount, 'allowed', a?.riskScore ?? 0);
          } else {
            addOperation(s, ap.agentId, ap.description, 'По согласованию', ap.amount, 'approval_rejected', a?.riskScore ?? 0, 7);
          }
        });
        pushToast(approve ? 'Операция одобрена, шлюз вызывает провайдера' : 'Операция отклонена, резерв возвращён', approve ? 'good' : 'warning');
      },

      // ——— Операции и квитанции ———
      anchorPending() {
        const count = getState().operations.filter((o) => o.receipt?.status === 'pending').length;
        setState((s) => {
          const batch = `batch-${1100 + Math.floor(Math.random() * 800)}`;
          const tx = fakeHash(batch, 44);
          s.operations.forEach((o) => {
            if (o.receipt?.status === 'pending') {
              o.receipt.status = 'anchored';
              o.receipt.batchId = batch;
              o.receipt.txHash = tx;
              o.receipt.merklePath = [fakeHash(o.id + 'm1'), fakeHash(o.id + 'm2'), fakeHash(o.id + 'm3')];
            }
          });
        });
        return count;
      },

      // ——— ПО: поставщики, договоры, подписки, лицензии ———
      createVendor(v: Omit<Vendor, 'id'>) {
        const id = newId('v');
        setState((s) => { s.vendors.push({ ...v, id }); });
        pushToast(`Поставщик «${v.name}» добавлен`, 'good');
        return id;
      },
      saveContract(c: Omit<Contract, 'id'> & { id?: string }) {
        setState((s) => {
          const ex = c.id ? s.contracts.find((x) => x.id === c.id) : undefined;
          if (ex) Object.assign(ex, c);
          else s.contracts.push({ ...c, id: newId('c') });
        });
        pushToast('Договор сохранён', 'good');
      },
      toggleAutoRenew(id: string) {
        setState((s) => { const c = s.contracts.find((x) => x.id === id); if (c) c.autoRenew = !c.autoRenew; });
        pushToast('Настройка автопродления изменена');
      },
      saveSubscription(sub: Omit<Subscription, 'id'> & { id?: string }) {
        const id = sub.id ?? newId('s');
        setState((s) => {
          const ex = s.subscriptions.find((x) => x.id === id);
          if (ex) Object.assign(ex, sub);
          else s.subscriptions.push({ ...sub, id });
        });
        pushToast('Подписка сохранена', 'good');
        return id;
      },
      revokeLicense(assignmentId: string) {
        setState((s) => { s.assignments = s.assignments.filter((a) => a.id !== assignmentId); });
        pushToast('Лицензия снята. Место освободилось', 'good');
      },
      assignLicense(subscriptionId: string, employeeId: string) {
        setState((s) => { s.assignments.push({ id: newId('la'), subscriptionId, employeeId, lastActive: null }); });
        pushToast('Лицензия назначена', 'good');
      },

      // ——— Находки ———
      findingAction(id: string, action: FindingAction, by: string, payload?: { ownerId?: string; date?: string; reason?: string }) {
        setState((s) => {
          const f = s.findings.find((x) => x.id === id);
          if (!f || !FINDING_TRANSITIONS[f.status].includes(action)) return;
          const log = (text: string) => f.history.push({ at: new Date().toISOString(), by, text });
          switch (action) {
            case 'assign': {
              f.ownerId = payload?.ownerId;
              f.status = 'review';
              log(`Назначен владелец: ${s.users.find((u) => u.id === payload?.ownerId)?.name ?? '—'}`);
              break;
            }
            case 'accept': f.status = 'accepted'; log('Принята'); break;
            case 'postpone': f.status = 'postponed'; f.postponedUntil = payload?.date; log(`Отложена до ${payload?.date ? new Date(payload.date).toLocaleDateString('ru-RU') : '—'}`); break;
            case 'reject': f.status = 'rejected'; f.rejectReason = payload?.reason; log(`Отклонена: ${payload?.reason ?? ''}`); break;
            case 'createTask': f.status = 'in_progress'; log(`Создана задача${payload?.reason ? `: ${payload.reason}` : ''}`); break;
            case 'confirm': f.status = 'confirmed'; log('Экономия видна в следующем счёте. Подтверждена'); notify(s, `Подтверждена экономия ${fmtRub(f.expectedSavings)}/мес: ${f.title}`, 'good', `/findings/${f.id}`); break;
            case 'missInvoice':
              f.missedInvoices += 1;
              if (f.missedInvoices >= 2) { f.status = 'review'; f.missedInvoices = 0; log('Экономии нет два счёта подряд. Вернулась на проверку'); }
              else log('Экономии нет в очередном счёте (1 из 2)');
              break;
            case 'autoClose': f.status = 'auto_closed'; log('Условие исчезло. Закрыта автоматически'); break;
            case 'reopen': f.status = 'new'; log('Условие вернулось. Находка открыта снова'); break;
            case 'dateReached': f.status = 'new'; f.postponedUntil = undefined; log('Наступила дата. Находка снова новая'); break;
          }
        });
        pushToast(`${FINDING_ACTIONS[action]}: готово`, 'good');
      },

      // ——— Импорт ———
      createImport(fileName: string, by: string, vendorId?: string): string {
        const id = newId('imp');
        const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
        const type: ImportType = ['xlsx', 'xls'].includes(ext) ? 'excel' : ext === 'csv' ? 'csv' : ext === 'pdf' ? 'pdf' : ['jpg', 'jpeg', 'png', 'heic'].includes(ext) ? 'scan' : 'bank';
        setState((s) => {
          const job: ImportJob = { id, fileName, type, vendorId, uploadedAt: new Date().toISOString(), uploadedBy: by, status: 'mapping', rows: 0, total: 0, columns: [], mapping: {}, errors: [], reviewTasks: [], templateSaved: false };
          const knownTemplate = vendorId && s.imports.some((i) => i.vendorId === vendorId && i.templateSaved && (i.type === 'excel' || i.type === 'csv'));
          if (type === 'excel' || type === 'csv') {
            job.columns = ['Наименование услуги', 'Пользователь', 'Почта', 'Стоимость, руб', 'Дата начала', 'Дата окончания', 'Последний вход'];
            // Подсказка сопоставления: словарь синонимов + LLM на бэкенде
            job.mapping = { 'Наименование услуги': 'Продукт', 'Пользователь': 'Сотрудник', 'Почта': 'Email', 'Стоимость, руб': 'Сумма', 'Дата начала': 'Период начала', 'Дата окончания': 'Период окончания', 'Последний вход': 'Последняя активность' };
            if (knownTemplate) { job.status = 'done'; job.rows = 36; job.total = 41800; job.templateSaved = true; }
          } else if (type === 'pdf' || type === 'scan') {
            job.status = 'review';
            job.rows = type === 'pdf' ? 14 : 6;
            job.total = type === 'pdf' ? 86300 : 18600;
            job.reviewTasks = [
              { id: newId('rt'), row: 3, field: 'ИНН поставщика', value: type === 'scan' ? '77О4 1234 56' : '7704123456', confidence: type === 'scan' ? 0.41 : 0.71, resolved: false },
              { id: newId('rt'), row: 9, field: 'Сумма', value: '12 4OO,00', confidence: 0.55, resolved: false },
            ];
          } else {
            job.status = 'done'; job.rows = 128; job.total = 742100; job.templateSaved = true;
          }
          s.imports.unshift(job);
          notify(s, `Файл «${fileName}» загружен`, 'info', `/imports/${id}`);
        });
        pushToast('Файл загружен в Bronze (S3). Начат разбор', 'info');
        return id;
      },
      confirmMapping(id: string, mapping: Record<string, string>, saveTemplate: boolean) {
        setState((s) => {
          const j = s.imports.find((x) => x.id === id);
          if (!j) return;
          j.mapping = mapping;
          j.templateSaved = saveTemplate;
          j.rows = 42;
          j.total = 58600;
          j.errors = [
            { row: 17, message: 'Сумма «—» не число' },
            { row: 29, message: 'Дубль строки 28: тот же сотрудник и период' },
          ];
          const hasAmount = Object.values(mapping).includes('Сумма');
          if (!hasAmount) j.errors.unshift({ row: 0, message: 'Не выбрана колонка «Сумма»: суммы не импортированы' });
          j.status = 'done';
          notify(s, `Импорт «${j.fileName}» завершён: ${j.rows - j.errors.length} строк, ошибок ${j.errors.length}`, j.errors.length ? 'warning' : 'good', `/imports/${id}`);
        });
        pushToast(saveTemplate ? 'Колонки сопоставлены, шаблон поставщика сохранён' : 'Колонки сопоставлены', 'good');
      },
      resolveTask(importId: string, taskId: string, value: string) {
        setState((s) => {
          const j = s.imports.find((x) => x.id === importId);
          const t = j?.reviewTasks.find((x) => x.id === taskId);
          if (!j || !t) return;
          t.value = value;
          t.resolved = true;
          t.confidence = 1;
          if (j.reviewTasks.every((x) => x.resolved)) {
            j.status = 'done';
            j.templateSaved = true;
            notify(s, `Ручная проверка «${j.fileName}» завершена, записи переданы в Silver`, 'good', `/imports/${importId}`);
          }
        });
        pushToast('Исправление сохранено как пример поставщика', 'good');
      },
      retryImport(id: string) {
        setState((s) => {
          const j = s.imports.find((x) => x.id === id);
          if (!j) return;
          j.status = 'review';
          j.errors = [];
          j.rows = 4;
          j.total = 6200;
          j.reviewTasks = [{ id: newId('rt'), row: 2, field: 'Сумма', value: '6 2OO,00', confidence: 0.52, resolved: false }];
        });
        pushToast('Повторное распознавание запущено', 'info');
      },
      deleteImport(id: string) {
        setState((s) => { s.imports = s.imports.filter((x) => x.id !== id); });
        pushToast('Импорт удалён');
      },

      // ——— Оргструктура и пользователи ———
      createDepartment(name: string) {
        setState((s) => { s.departments.push({ id: newId('d'), name }); });
        pushToast(`Отдел «${name}» создан`, 'good');
      },
      saveEmployee(e: Omit<Employee, 'id' | 'active'> & { id?: string }) {
        setState((s) => {
          const ex = e.id ? s.employees.find((x) => x.id === e.id) : undefined;
          if (ex) Object.assign(ex, e);
          else s.employees.push({ ...e, id: newId('e'), active: true });
        });
        pushToast('Сотрудник сохранён', 'good');
      },
      setEmployeeActive(id: string, active: boolean) {
        setState((s) => {
          const e = s.employees.find((x) => x.id === id);
          if (!e) return;
          e.active = active;
          const licenses = s.assignments.filter((a) => a.employeeId === id);
          if (!active && licenses.length) {
            const subs = licenses.map((l) => s.subscriptions.find((x) => x.id === l.subscriptionId)).filter(Boolean) as Subscription[];
            const savings = subs.reduce((sum, x) => sum + x.pricePerSeat, 0);
            const fid = newId('f');
            s.findings.unshift({
              id: fid, rule: 'offboarded', title: `Лицензии уволенного: ${e.name}`,
              description: `У сотрудника остались лицензии: ${subs.map((x) => x.product).join(', ')}.`,
              subscriptionId: subs[0]?.id, expectedSavings: savings, status: 'new', missedInvoices: 0,
              createdAt: new Date().toISOString(), history: [{ at: new Date().toISOString(), by: 'Движок аудита', text: 'Находка создана по правилу offboarded' }],
            });
            notify(s, `Новая находка: лицензии уволенного (${e.name})`, 'warning', `/findings/${fid}`);
          }
        });
        pushToast(active ? 'Сотрудник восстановлен' : 'Сотрудник отмечен уволенным. Движок аудита проверит его лицензии', active ? 'good' : 'warning');
      },
      inviteUser(u: Omit<User, 'id'>) {
        setState((s) => { s.users.push({ ...u, id: newId('u') }); });
        pushToast(`Приглашение отправлено на ${u.email}`, 'good');
      },
      setUserRole(id: string, role: Role) {
        setState((s) => { const u = s.users.find((x) => x.id === id); if (u) u.role = role; });
        pushToast('Роль изменена (в Keycloak)', 'good');
      },
      removeUser(id: string) {
        setState((s) => { s.users = s.users.filter((u) => u.id !== id); });
        pushToast('Доступ пользователя закрыт');
      },

      // ——— Отчёты ———
      createReport(name: string, format: 'xlsx' | 'pdf', period: string, by: string) {
        const id = newId('r');
        setState((s) => { s.reports.unshift({ id, name, format, period, createdAt: new Date().toISOString(), createdBy: by }); });
        pushToast('Отчёт сформирован', 'good');
        return id;
      },
      deleteReport(id: string) {
        setState((s) => { s.reports = s.reports.filter((r) => r.id !== id); });
        pushToast('Отчёт удалён');
      },

      // ——— Имитация потока событий (в продакшене — SSE от ядра) ———
      simulateTick() {
        // Решение принимается по текущему снимку, изменение применяется в setState
        const snap = getState();
        const active = snap.agents.filter((a) => a.status === 'active');
        const suspended = snap.agents.filter((a) => a.status === 'suspended');
        const roll = Math.random();
        const pick = <T,>(arr: T[]) => arr[Math.floor(Math.random() * arr.length)];
        const anchorDue = (s: AppState) => {
          const pending = s.operations.filter((o) => o.receipt?.status === 'pending');
          if (!pending.length || new Date(pending[pending.length - 1].at).getTime() > Date.now() - 60000) return;
          const batch = `batch-${1100 + Math.floor(Math.random() * 800)}`;
          pending.forEach((o) => {
            o.receipt!.status = 'anchored';
            o.receipt!.batchId = batch;
            o.receipt!.txHash = fakeHash(batch, 44);
            o.receipt!.merklePath = [fakeHash(o.id + 'm1'), fakeHash(o.id + 'm2')];
          });
        };
        if (suspended.length && roll < 0.12) {
          const a = pick(suspended);
          setState((s) => { addOperation(s, a.id, 'claude-opus-5-5', 'Anthropic', 0, '423_agent_suspended', a.riskScore, 4); anchorDue(s); });
          return;
        }
        if (!active.length) return;
        const a = pick(active);
        const [resource, vendor, base] = pick<[string, string, number]>([['gpt-5-mini', 'OpenAI', 4], ['claude-sonnet-5-5', 'Anthropic', 14], ['GigaChat-Pro', 'Сбер GigaChat', 6], ['gpt-5', 'OpenAI', 36]]);
        const cost = Math.round(base * (0.5 + Math.random() * 1.5) * 100) / 100;
        const over = budgetLeft(snap, a.id, cost);
        if (over) {
          setState((s) => {
            addOperation(s, a.id, resource, vendor, cost, '402_budget_exceeded', a.riskScore, 5);
            notify(s, `«${a.name}»: 402 budget_exceeded, исчерпан бюджет «${over.name}»`, 'critical', '/operations');
            anchorDue(s);
          });
          pushToast(`«${a.name}» упёрся в бюджет «${over.name}»`, 'critical');
        } else if (roll > 0.93) {
          setState((s) => { addOperation(s, a.id, resource, vendor, 0, '429_rate_limited', a.riskScore, 4); anchorDue(s); });
        } else if (roll > 0.88) {
          const amount = Math.round(400 + Math.random() * 2600);
          const ap: Approval = { id: newId('ap'), agentId: a.id, description: `Платный запрос: ${resource}, пакетная обработка`, amount, reason: 'Сумма выше порога мандата', createdAt: new Date().toISOString(), deadline: new Date(Date.now() + 2 * 3600000).toISOString(), status: 'pending' };
          setState((s) => {
            s.approvals.unshift(ap);
            notify(s, `Новое согласование: «${agentName(s, a.id)}», ${fmtRub(amount)}`, 'warning', '/approvals');
            anchorDue(s);
          });
          pushToast(`Новое согласование: «${a.name}», ${fmtRub(amount)}`, 'warning');
        } else {
          setState((s) => { addOperation(s, a.id, resource, vendor, cost, 'allowed', a.riskScore); anchorDue(s); });
        }
      },
    };
  }, [setState, pushToast, getState]);
}

type Actions = ReturnType<typeof useActions>;

interface StoreValue {
  state: AppState;
  actions: Actions;
  toasts: Toast[];
  dismissToast: (id: string) => void;
  toast: (text: string, level?: Notification['level']) => void;
  live: boolean;
  setLive: (v: boolean) => void;
}

const StoreContext = createContext<StoreValue | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, setRaw] = useState<AppState>(loadState);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [live, setLive] = useState(true);
  const first = useRef(true);

  const setState = useCallback((fn: (s: AppState) => void) => {
    setRaw((prev) => {
      const next = structuredClone(prev);
      fn(next);
      return next;
    });
  }, []);

  const dismissToast = useCallback((id: string) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const toast = useCallback((text: string, level: Notification['level'] = 'info') => {
    const id = newId('t');
    setToasts((t) => [...t.slice(-3), { id, text, level }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);

  const stateRef = useRef(state);
  stateRef.current = state;
  const getState = useCallback(() => stateRef.current, []);
  const actions = useActions(setState, toast, getState);

  useEffect(() => {
    if (first.current) { first.current = false; return; }
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch { /* приватный режим */ }
  }, [state]);

  useEffect(() => {
    if (!live) return;
    const t = setInterval(() => actions.simulateTick(), 15000);
    return () => clearInterval(t);
  }, [live, actions]);

  const value = useMemo(() => ({ state, actions, toasts, dismissToast, toast, live, setLive }), [state, actions, toasts, dismissToast, toast, live]);
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore() {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error('useStore вне StoreProvider');
  return ctx;
}

// Удобные селекторы
export function useLookup() {
  const { state } = useStore();
  return useMemo(() => ({
    agent: (id?: string) => state.agents.find((a) => a.id === id),
    dept: (id?: string) => state.departments.find((d) => d.id === id),
    employee: (id?: string) => state.employees.find((e) => e.id === id),
    user: (id?: string) => state.users.find((u) => u.id === id),
    vendor: (id?: string) => state.vendors.find((v) => v.id === id),
    mandate: (id?: string) => state.mandates.find((m) => m.id === id),
    subscription: (id?: string) => state.subscriptions.find((s) => s.id === id),
    contract: (id?: string) => state.contracts.find((c) => c.id === id),
    budgetOfAgent: (id?: string) => state.budgets.find((b) => b.level === 'agent' && b.refId === id),
  }), [state]);
}

export type { Finding };
