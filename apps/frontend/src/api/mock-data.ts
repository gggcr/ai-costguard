// Демо-компания «Северный ветер». Все данные синтетические.
import type {
  Agent, AppState, Approval, Asset, AssetAssignment, Budget, Connection, ConnectionRun, Contract, CostPoint, Department, Employee, Finding,
  GatewayCode, ImportJob, LicenseAssignment, Mandate, Notification, Operation, Report,
  Subscription, SystemType, User, Vendor, VirtualKey,
} from './types';
import { fakeHash, rng } from '../lib';

const DAY = 86400000;
const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
const isoAhead = (msAhead: number) => new Date(Date.now() + msAhead).toISOString();

const departments: Department[] = [
  { id: 'd-dev', name: 'Разработка' },
  { id: 'd-sales', name: 'Продажи' },
  { id: 'd-mkt', name: 'Маркетинг' },
  { id: 'd-support', name: 'Поддержка' },
  { id: 'd-fin', name: 'Финансы' },
  { id: 'd-it', name: 'ИТ' },
];

const employees: Employee[] = [
  { id: 'e-01', name: 'Анна Ковалёва', email: 'a.kovaleva@sever.demo', departmentId: 'd-fin', position: 'Генеральный директор', active: true },
  { id: 'e-02', name: 'Игорь Лебедев', email: 'i.lebedev@sever.demo', departmentId: 'd-fin', position: 'Финансовый директор', active: true },
  { id: 'e-03', name: 'Мария Соколова', email: 'm.sokolova@sever.demo', departmentId: 'd-it', position: 'Руководитель ИТ', active: true },
  { id: 'e-04', name: 'Павел Орлов', email: 'p.orlov@sever.demo', departmentId: 'd-support', position: 'Руководитель поддержки', active: true },
  { id: 'e-05', name: 'Елена Миронова', email: 'e.mironova@sever.demo', departmentId: 'd-fin', position: 'Внутренний аудитор', active: true },
  { id: 'e-06', name: 'Дмитрий Волков', email: 'd.volkov@sever.demo', departmentId: 'd-dev', position: 'Тимлид', active: true },
  { id: 'e-07', name: 'Ольга Новикова', email: 'o.novikova@sever.demo', departmentId: 'd-dev', position: 'Разработчик', active: true },
  { id: 'e-08', name: 'Сергей Морозов', email: 's.morozov@sever.demo', departmentId: 'd-dev', position: 'Разработчик', active: false },
  { id: 'e-09', name: 'Татьяна Зайцева', email: 't.zaytseva@sever.demo', departmentId: 'd-sales', position: 'Руководитель продаж', active: true },
  { id: 'e-10', name: 'Алексей Павлов', email: 'a.pavlov@sever.demo', departmentId: 'd-sales', position: 'Менеджер', active: true },
  { id: 'e-11', name: 'Наталья Белова', email: 'n.belova@sever.demo', departmentId: 'd-sales', position: 'Менеджер', active: false },
  { id: 'e-12', name: 'Кирилл Андреев', email: 'k.andreev@sever.demo', departmentId: 'd-mkt', position: 'Маркетолог', active: true },
  { id: 'e-13', name: 'Юлия Смирнова', email: 'y.smirnova@sever.demo', departmentId: 'd-mkt', position: 'Дизайнер', active: true },
  { id: 'e-14', name: 'Роман Кузнецов', email: 'r.kuznetsov@sever.demo', departmentId: 'd-support', position: 'Специалист поддержки', active: true },
  { id: 'e-15', name: 'Виктория Егорова', email: 'v.egorova@sever.demo', departmentId: 'd-support', position: 'Специалист поддержки', active: true },
  { id: 'e-16', name: 'Максим Фёдоров', email: 'm.fedorov@sever.demo', departmentId: 'd-it', position: 'Системный администратор', active: true },
];

const users: User[] = [
  { id: 'u-owner', name: 'Анна Ковалёва', email: 'a.kovaleva@sever.demo', role: 'owner', employeeId: 'e-01' },
  { id: 'u-fin', name: 'Игорь Лебедев', email: 'i.lebedev@sever.demo', role: 'finance', employeeId: 'e-02' },
  { id: 'u-it', name: 'Мария Соколова', email: 'm.sokolova@sever.demo', role: 'it_admin', employeeId: 'e-03' },
  { id: 'u-ao', name: 'Павел Орлов', email: 'p.orlov@sever.demo', role: 'agent_owner', employeeId: 'e-04' },
  { id: 'u-aud', name: 'Елена Миронова', email: 'e.mironova@sever.demo', role: 'auditor', employeeId: 'e-05' },
  { id: 'u-emp', name: 'Ольга Новикова', email: 'o.novikova@sever.demo', role: 'employee', employeeId: 'e-07' },
];

const mandates: Mandate[] = [
  { id: 'm-std', name: 'Стандартный', models: ['gpt-5-mini', 'claude-sonnet-5-5', 'GigaChat-Pro'], vendors: ['OpenAI', 'Anthropic', 'Сбер GigaChat'], maxPerOperation: 500, approvalThreshold: 300, hours: 'круглосуточно', status: 'published', version: 4, updatedAt: iso(12 * DAY) },
  { id: 'm-research', name: 'Исследования рынка', models: ['gpt-5', 'claude-opus-5-5', 'YandexGPT-5'], vendors: ['OpenAI', 'Anthropic', 'Yandex Cloud', 'СПАРК-Интерфакс'], maxPerOperation: 3000, approvalThreshold: 1500, hours: '09:00–21:00', status: 'published', version: 2, updatedAt: iso(30 * DAY) },
  { id: 'm-fin', name: 'Финансы — строгий', models: ['GigaChat-Pro', 'YandexGPT-5'], vendors: ['Сбер GigaChat', 'Yandex Cloud'], maxPerOperation: 150, approvalThreshold: 80, hours: '08:00–20:00', status: 'published', version: 1, updatedAt: iso(45 * DAY) },
  { id: 'm-dev', name: 'Разработка (черновик)', models: ['claude-opus-5-5', 'gpt-5'], vendors: ['Anthropic', 'OpenAI', 'Yandex Cloud'], maxPerOperation: 1200, approvalThreshold: 800, hours: 'круглосуточно', status: 'draft', version: 1, updatedAt: iso(1 * DAY) },
];

const agents: Agent[] = [
  { id: 'a-support', name: 'Бот поддержки', description: 'Отвечает клиентам в чате и почте, эскалирует сложные обращения', framework: 'LangChain', departmentId: 'd-support', ownerId: 'e-04', status: 'active', riskScore: 18, riskReason: 'Поведение в пределах базовой линии', mandateId: 'm-std', createdAt: iso(160 * DAY) },
  { id: 'a-sales', name: 'Агент лидогенерации', description: 'Ищет компании, обогащает карточки лидов, пишет первые письма', framework: 'CrewAI', departmentId: 'd-sales', ownerId: 'e-09', status: 'active', riskScore: 42, riskReason: 'Новый поставщик данных за последние сутки', mandateId: 'm-research', createdAt: iso(95 * DAY) },
  { id: 'a-research', name: 'Исследователь рынка', description: 'Собирает отчёты о конкурентах из открытых источников и платных баз', framework: 'AutoGen', departmentId: 'd-mkt', ownerId: 'e-12', status: 'suspended', riskScore: 91, riskReason: 'Петля: 46 похожих запросов за 5 минут, контекст растёт', mandateId: 'm-research', createdAt: iso(70 * DAY) },
  { id: 'a-code', name: 'Ревьюер кода', description: 'Проверяет merge request и предлагает исправления', framework: 'Claude Agent SDK', departmentId: 'd-dev', ownerId: 'e-06', status: 'active', riskScore: 12, riskReason: 'Поведение в пределах базовой линии', mandateId: 'm-std', createdAt: iso(120 * DAY) },
  { id: 'a-fin', name: 'Сверка счетов', description: 'Сверяет входящие счета с договорами и выписками', framework: 'n8n (no-code)', departmentId: 'd-fin', ownerId: 'e-02', status: 'active', riskScore: 27, riskReason: 'Операции ночью: 3 за неделю', mandateId: 'm-fin', createdAt: iso(60 * DAY) },
  { id: 'a-content', name: 'Генератор контента', description: 'Готовит тексты и изображения для соцсетей', framework: 'OpenAI Agents SDK', departmentId: 'd-mkt', ownerId: 'e-12', status: 'active', riskScore: 63, riskReason: 'Всплеск трат: в 3,4 раза выше базовой линии агента', mandateId: 'm-std', createdAt: iso(40 * DAY) },
];

const keys: VirtualKey[] = agents.flatMap((a, i) => [
  { id: `k-${a.id}-1`, agentId: a.id, prefix: `cg_live_${fakeHash(a.id, 6)}`, createdAt: iso((30 + i * 5) * DAY), status: 'active' as const },
  ...(i % 2 === 0 ? [{ id: `k-${a.id}-0`, agentId: a.id, prefix: `cg_live_${fakeHash(a.id + 'old', 6)}`, createdAt: iso((90 + i * 5) * DAY), status: 'revoked' as const }] : []),
]);

const providerByModel: Record<string, string> = {
  'gpt-5-mini': 'OpenAI', 'gpt-5': 'OpenAI', 'claude-sonnet-5-5': 'Anthropic', 'claude-opus-5-5': 'Anthropic',
  'GigaChat-Pro': 'Сбер GigaChat', 'YandexGPT-5': 'Yandex Cloud', 'Карточка компании': 'СПАРК-Интерфакс',
  'GPU-инстанс, 1 ч': 'Yandex Cloud', 'Генерация изображения': 'OpenAI',
};

const resourcesByAgent: Record<string, string[]> = {
  'a-support': ['gpt-5-mini', 'claude-sonnet-5-5', 'GigaChat-Pro'],
  'a-sales': ['gpt-5', 'Карточка компании', 'claude-sonnet-5-5'],
  'a-research': ['claude-opus-5-5', 'Карточка компании', 'YandexGPT-5'],
  'a-code': ['claude-opus-5-5', 'claude-sonnet-5-5'],
  'a-fin': ['GigaChat-Pro', 'YandexGPT-5'],
  'a-content': ['gpt-5', 'Генерация изображения', 'GPU-инстанс, 1 ч'],
};

const stepByCode: Partial<Record<GatewayCode, number>> = {
  '401_unauthorized': 1, '423_agent_suspended': 4, '403_policy_denied': 2, '409_duplicate_operation': 3,
  '429_rate_limited': 4, '402_budget_exceeded': 5, approval_rejected: 7,
};

function makeReceipt(opId: string, i: number, pending: boolean) {
  const hash = fakeHash(opId);
  return {
    hash,
    signature: fakeHash(opId + 'sig', 128),
    prevHash: fakeHash(`op-${i - 1}`),
    status: pending ? ('pending' as const) : ('anchored' as const),
    batchId: pending ? undefined : `batch-${Math.floor(i / 8) + 1040}`,
    txHash: pending ? undefined : fakeHash(`tx-${Math.floor(i / 8)}`, 44),
    merklePath: pending ? undefined : [fakeHash(opId + 'm1'), fakeHash(opId + 'm2'), fakeHash(opId + 'm3')],
  };
}

function makeOperations(): Operation[] {
  const r = rng(42);
  const ops: Operation[] = [];
  const N = 140;
  for (let i = 0; i < N; i++) {
    const agent = agents[Math.floor(r() * agents.length)];
    const res = resourcesByAgent[agent.id][Math.floor(r() * resourcesByAgent[agent.id].length)];
    const base = res === 'GPU-инстанс, 1 ч' ? 620 : res === 'Карточка компании' ? 180 : res.includes('opus') || res === 'gpt-5' ? 38 : 6;
    const cost = Math.round(base * (0.4 + r() * 1.6) * 100) / 100;
    const roll = r();
    let code: GatewayCode = 'allowed';
    if (agent.id === 'a-research' && i > N - 25) code = roll < 0.6 ? '423_agent_suspended' : '409_duplicate_operation';
    else if (roll > 0.95) code = '402_budget_exceeded';
    else if (roll > 0.92) code = '403_policy_denied';
    else if (roll > 0.9) code = '429_rate_limited';
    else if (roll > 0.885) code = '409_duplicate_operation';
    const id = `op-${String(i + 1).padStart(4, '0')}`;
    const at = iso((N - i) * (14 * DAY) / N + Math.floor(r() * 3600000));
    ops.push({
      id, agentId: agent.id, at, vendor: providerByModel[res], resource: res,
      cost: code === 'allowed' ? cost : 0,
      code,
      risk: Math.min(100, Math.round(agent.riskScore * (0.6 + r() * 0.6))),
      failedStep: stepByCode[code],
      receipt: code === 'allowed' ? makeReceipt(id, i, i > N - 6) : undefined,
    });
  }
  return ops.sort((a, b) => b.at.localeCompare(a.at));
}

const operations = makeOperations();

const sumAgent = (id: string) =>
  Math.round(operations.filter((o) => o.agentId === id && o.code === 'allowed').reduce((s, o) => s + o.cost, 0) * 7.3);

const agentBudgets: Budget[] = agents.map((a) => {
  const spent = sumAgent(a.id);
  const limitByAgent: Record<string, number> = { 'a-support': 60000, 'a-sales': 90000, 'a-research': 70000, 'a-code': 45000, 'a-fin': 20000, 'a-content': 75000 };
  return { id: `b-${a.id}`, name: a.name, level: 'agent', refId: a.id, parentId: `b-${a.departmentId}`, limit: limitByAgent[a.id], spent: Math.min(spent, limitByAgent[a.id] * 0.97), reserved: Math.round(r2(a.id) * 900) };
});
function r2(s: string) { return (parseInt(fakeHash(s, 4), 16) % 100) / 100; }

const deptBudgets: Budget[] = departments.map((d) => {
  const children = agentBudgets.filter((b) => b.parentId === `b-${d.id}`);
  const limit = Math.max(30000, children.reduce((s, b) => s + b.limit, 0) + 20000);
  return { id: `b-${d.id}`, name: d.name, level: 'department', refId: d.id, parentId: 'b-company', limit, spent: children.reduce((s, b) => s + b.spent, 0), reserved: children.reduce((s, b) => s + b.reserved, 0) };
});

const budgets: Budget[] = [
  { id: 'b-company', name: 'Компания: ИИ-агенты, ноябрь', level: 'company', limit: 480000, spent: deptBudgets.reduce((s, b) => s + b.spent, 0), reserved: deptBudgets.reduce((s, b) => s + b.reserved, 0) },
  ...deptBudgets,
  ...agentBudgets,
];

const approvals: Approval[] = [
  { id: 'ap-1', agentId: 'a-sales', description: 'Покупка выгрузки 400 карточек компаний (СПАРК-Интерфакс)', amount: 2400, reason: 'Сумма выше порога мандата (1 500 ₽)', createdAt: iso(40 * 60000), deadline: isoAhead(80 * 60000), status: 'pending' },
  { id: 'ap-2', agentId: 'a-content', description: 'GPU-инстанс на 3 часа для генерации видеоролика', amount: 1860, reason: 'Риск-оценка 63: всплеск трат, нужно решение человека', createdAt: iso(15 * 60000), deadline: isoAhead(105 * 60000), status: 'pending' },
  { id: 'ap-3', agentId: 'a-code', description: 'Анализ репозитория целиком моделью claude-opus-5-5', amount: 410, reason: 'Сумма выше порога мандата (300 ₽)', createdAt: iso(5 * 60000), deadline: isoAhead(55 * 60000), status: 'pending' },
  { id: 'ap-4', agentId: 'a-research', description: 'Отчёт о рынке логистики (платная база)', amount: 2900, reason: 'Сумма выше порога мандата (1 500 ₽)', createdAt: iso(2 * DAY), deadline: iso(2 * DAY - 7200000), status: 'rejected', decidedBy: 'Игорь Лебедев', comment: 'Есть свежий отчёт за сентябрь, повторно не покупаем' },
  { id: 'ap-5', agentId: 'a-sales', description: 'Обогащение 120 лидов контактами', amount: 1700, reason: 'Сумма выше порога мандата (1 500 ₽)', createdAt: iso(3 * DAY), deadline: iso(3 * DAY - 7200000), status: 'approved', decidedBy: 'Татьяна Зайцева', comment: 'Ок, под ноябрьскую кампанию' },
  { id: 'ap-6', agentId: 'a-support', description: 'Перевод базы знаний на 3 языка', amount: 640, reason: 'Сумма выше порога мандата (300 ₽)', createdAt: iso(5 * DAY), deadline: iso(5 * DAY - 7200000), status: 'expired' },
];

const vendors: Vendor[] = [
  { id: 'v-ms', name: 'Microsoft 365', inn: '7704..21', category: 'Офисные пакеты' },
  { id: 'v-y360', name: 'Яндекс 360', inn: '7736..63', category: 'Офисные пакеты' },
  { id: 'v-atl', name: 'Atlassian (Jira, Confluence)', inn: '—', category: 'Управление проектами' },
  { id: 'v-figma', name: 'Figma', inn: '—', category: 'Дизайн' },
  { id: 'v-miro', name: 'Miro', inn: '—', category: 'Совместная работа' },
  { id: 'v-b24', name: 'Битрикс24', inn: '7717..09', category: 'CRM' },
  { id: 'v-kontur', name: 'Контур', inn: '6663..90', category: 'Бухгалтерия и ЭДО' },
  { id: 'v-kasp', name: 'Kaspersky', inn: '7713..42', category: 'Безопасность' },
  { id: 'v-openai', name: 'OpenAI', inn: '—', category: 'ИИ-провайдеры' },
  { id: 'v-anthropic', name: 'Anthropic', inn: '—', category: 'ИИ-провайдеры' },
  { id: 'v-sber', name: 'Сбер GigaChat', inn: '7707..93', category: 'ИИ-провайдеры' },
  { id: 'v-yc', name: 'Yandex Cloud', inn: '7704..66', category: 'Облака' },
];

const contracts: Contract[] = [
  { id: 'c-ms', vendorId: 'v-ms', number: 'MS-EA-2025/118', startDate: '2025-12-01', endDate: '2026-11-30', noticeDays: 30, autoRenew: true, amount: 1296000 },
  { id: 'c-atl', vendorId: 'v-atl', number: 'ATL-4471', startDate: '2026-03-01', endDate: '2027-02-28', noticeDays: 30, autoRenew: true, amount: 432000 },
  { id: 'c-b24', vendorId: 'v-b24', number: 'Б24-0921', startDate: '2026-01-15', endDate: '2026-12-14', noticeDays: 14, autoRenew: true, amount: 297600 },
  { id: 'c-kontur', vendorId: 'v-kontur', number: 'К-77812', startDate: '2026-06-01', endDate: '2027-05-31', noticeDays: 30, autoRenew: false, amount: 84000 },
  { id: 'c-yc', vendorId: 'v-yc', number: 'YC-2026-0042', startDate: '2026-01-01', endDate: '2026-12-31', noticeDays: 60, autoRenew: true, amount: 1800000 },
];

const subscriptions: Subscription[] = [
  { id: 's-ms', vendorId: 'v-ms', product: 'Microsoft 365 E3', contractId: 'c-ms', departmentId: 'd-it', seats: 14, pricePerSeat: 2900, billingPeriod: 'year', renewalDate: '2026-11-30' },
  { id: 's-y360', vendorId: 'v-y360', product: 'Яндекс 360 для бизнеса', departmentId: 'd-it', seats: 10, pricePerSeat: 690, billingPeriod: 'month', renewalDate: '2026-11-20' },
  { id: 's-jira', vendorId: 'v-atl', product: 'Jira Software Standard', contractId: 'c-atl', departmentId: 'd-dev', seats: 12, pricePerSeat: 1450, billingPeriod: 'year', renewalDate: '2027-02-28' },
  { id: 's-figma', vendorId: 'v-figma', product: 'Figma Professional', departmentId: 'd-mkt', seats: 5, pricePerSeat: 1500, billingPeriod: 'month', renewalDate: '2026-11-18' },
  { id: 's-miro', vendorId: 'v-miro', product: 'Miro Business', departmentId: 'd-dev', seats: 8, pricePerSeat: 1300, billingPeriod: 'month', renewalDate: '2026-11-25' },
  { id: 's-b24', vendorId: 'v-b24', product: 'Битрикс24 Профессиональный', contractId: 'c-b24', departmentId: 'd-sales', seats: 50, pricePerSeat: 496, billingPeriod: 'year', renewalDate: '2026-12-14' },
  { id: 's-kontur', vendorId: 'v-kontur', product: 'Контур.Бухгалтерия', contractId: 'c-kontur', departmentId: 'd-fin', seats: 3, pricePerSeat: 2333, billingPeriod: 'year', renewalDate: '2027-05-31' },
  { id: 's-kasp', vendorId: 'v-kasp', product: 'Kaspersky Endpoint Security', departmentId: 'd-it', seats: 20, pricePerSeat: 310, billingPeriod: 'year', renewalDate: '2027-01-10' },
];

function makeAssignments(): LicenseAssignment[] {
  const r = rng(7);
  const list: LicenseAssignment[] = [];
  subscriptions.forEach((s) => {
    const pool = s.departmentId === 'd-it' || s.id === 's-b24' || s.id === 's-kasp' ? employees : employees.filter((e) => e.departmentId === s.departmentId || r() > 0.7);
    const count = Math.min(s.seats, pool.length, s.id === 's-b24' ? 9 : s.seats - (s.id === 's-ms' ? 2 : 0));
    pool.slice(0, count).forEach((e, i) => {
      const inactive = !e.active || (s.id === 's-ms' && i % 4 === 3) || (s.id === 's-miro' && i % 3 === 2);
      list.push({ id: `la-${s.id}-${e.id}`, subscriptionId: s.id, employeeId: e.id, lastActive: inactive ? (r() > 0.5 ? null : iso((40 + Math.floor(r() * 60)) * DAY)) : iso(Math.floor(r() * 6) * DAY + 3600000) });
    });
  });
  return list;
}

const findings: Finding[] = [
  { id: 'f-1', rule: 'unused_licenses', title: 'Microsoft 365: 5 лицензий без входа больше 30 дней', description: 'Пять назначенных лицензий E3 не использовались больше 30 дней. Лицензии можно снять и не продлевать 30 ноября.', subscriptionId: 's-ms', expectedSavings: 14500, status: 'review', ownerId: 'u-it', missedInvoices: 0, createdAt: iso(6 * DAY), history: [{ at: iso(6 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу unused_licenses' }, { at: iso(5 * DAY), by: 'Анна Ковалёва', text: 'Назначен владелец: Мария Соколова' }] },
  { id: 'f-2', rule: 'offboarded', title: 'Лицензии уволенных: Сергей Морозов, Наталья Белова', description: 'У двух уволенных сотрудников остались лицензии в 4 сервисах.', subscriptionId: 's-jira', expectedSavings: 6380, status: 'new', missedInvoices: 0, createdAt: iso(2 * DAY), history: [{ at: iso(2 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу offboarded' }] },
  { id: 'f-3', rule: 'overlap', title: 'Пересекающиеся инструменты: Microsoft 365 и Яндекс 360', description: 'Почта и документы оплачиваются в двух сервисах для одного отдела ИТ.', subscriptionId: 's-y360', expectedSavings: 6900, status: 'accepted', ownerId: 'u-it', missedInvoices: 0, createdAt: iso(20 * DAY), history: [{ at: iso(20 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу overlap' }, { at: iso(18 * DAY), by: 'Анна Ковалёва', text: 'Назначен владелец: Мария Соколова' }, { at: iso(15 * DAY), by: 'Мария Соколова', text: 'Принята: переводим всех на Microsoft 365' }] },
  { id: 'f-4', rule: 'auto_renewal', title: 'Автопродление Битрикс24 через 38 дней', description: 'Используется 9 мест из 50. Нужно уведомить поставщика за 14 дней, чтобы сменить тариф.', subscriptionId: 's-b24', expectedSavings: 16900, status: 'in_progress', ownerId: 'u-fin', missedInvoices: 0, createdAt: iso(25 * DAY), history: [{ at: iso(25 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу auto_renewal' }, { at: iso(24 * DAY), by: 'Анна Ковалёва', text: 'Назначен владелец: Игорь Лебедев' }, { at: iso(22 * DAY), by: 'Игорь Лебедев', text: 'Принята' }, { at: iso(21 * DAY), by: 'Игорь Лебедев', text: 'Создана задача: письмо поставщику о смене тарифа' }] },
  { id: 'f-5', rule: 'ai_bypass', title: 'ИИ-расходы мимо шлюза: личная карта, OpenAI', description: 'В выписке за октябрь 3 платежа OpenAI с корпоративной карты маркетинга, агент не подключён к шлюзу.', expectedSavings: 4200, status: 'new', missedInvoices: 0, createdAt: iso(1 * DAY), history: [{ at: iso(1 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу ai_bypass' }] },
  { id: 'f-6', rule: 'oversized_plan', title: 'Figma Professional: 2 места без редактирования', description: 'Два пользователя только просматривают макеты, им достаточно бесплатного доступа.', subscriptionId: 's-figma', expectedSavings: 3000, status: 'confirmed', ownerId: 'u-it', missedInvoices: 0, createdAt: iso(70 * DAY), history: [{ at: iso(70 * DAY), by: 'Движок аудита', text: 'Находка создана' }, { at: iso(64 * DAY), by: 'Мария Соколова', text: 'Принята' }, { at: iso(63 * DAY), by: 'Мария Соколова', text: 'Создана задача' }, { at: iso(30 * DAY), by: 'Движок аудита', text: 'Экономия видна в счёте за октябрь. Подтверждена' }] },
  { id: 'f-7', rule: 'invoice_errors', title: 'Ошибка в счёте Yandex Cloud за октябрь', description: 'Строка «Хранилище» выставлена дважды на 7 400 ₽.', expectedSavings: 7400, status: 'postponed', ownerId: 'u-fin', postponedUntil: isoAhead(5 * DAY), missedInvoices: 0, createdAt: iso(9 * DAY), history: [{ at: iso(9 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу invoice_errors' }, { at: iso(8 * DAY), by: 'Игорь Лебедев', text: 'Отложена: ждём акт сверки' }] },
  { id: 'f-8', rule: 'annual_plan', title: 'Miro: помесячная оплата вместо годовой', description: 'Годовой тариф дешевле на 17%.', subscriptionId: 's-miro', expectedSavings: 1770, status: 'rejected', ownerId: 'u-it', rejectReason: 'Через 2 месяца переходим на другой сервис', missedInvoices: 0, createdAt: iso(40 * DAY), history: [{ at: iso(40 * DAY), by: 'Движок аудита', text: 'Находка создана' }, { at: iso(38 * DAY), by: 'Мария Соколова', text: 'Отклонена: через 2 месяца переходим на другой сервис' }] },
  { id: 'f-10', rule: 'asset.not-returned', title: 'Уволенный не сдал технику: Сергей Морозов', description: 'За уволенным сотрудником числятся Apple MacBook Air 13 M2 (инв. № INV-0103) и Apple iPhone 13 (инв. № INV-0203). Верните технику; сумму удержания решают кадры и бухгалтерия.', assetId: 'as-03', employeeId: 'e-08', expectedSavings: 169980, status: 'new', missedInvoices: 0, createdAt: iso(2 * DAY), history: [{ at: iso(2 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу asset.not-returned' }] },
  { id: 'f-11', rule: 'asset.duplicate', title: 'У сотрудника 2 ноутбука: Дмитрий Волков', description: 'За одним сотрудником числятся Apple MacBook Pro 14 M3 (инв. № INV-0102) и Lenovo ThinkPad X1 Carbon Gen 11 (инв. № INV-0104). Верните лишний на склад и выдайте тем, кто ждёт новую технику.', assetId: 'as-04', employeeId: 'e-06', expectedSavings: 189990, status: 'review', ownerId: 'u-it', missedInvoices: 0, createdAt: iso(4 * DAY), history: [{ at: iso(4 * DAY), by: 'Движок аудита', text: 'Находка создана по правилу asset.duplicate' }, { at: iso(3 * DAY), by: 'Анна Ковалёва', text: 'Назначен владелец: Мария Соколова' }] },
  { id: 'f-9', rule: 'agent_loop', title: 'Исследователь рынка: петля запросов', description: 'Детектор аномалий остановил агента: 46 похожих запросов за 5 минут. Без остановки агент потратил бы около 18 000 ₽ за сутки.', agentId: 'a-research', expectedSavings: 18000, status: 'review', ownerId: 'u-ao', missedInvoices: 0, createdAt: iso(3 * 3600000), history: [{ at: iso(3 * 3600000), by: 'Детектор аномалий', text: 'Агент остановлен, риск 91' }] },
];

const imports: ImportJob[] = [
  { id: 'imp-1', fileName: 'Выписка_октябрь_2026.txt', type: 'bank', uploadedAt: iso(2 * DAY), uploadedBy: 'Игорь Лебедев', status: 'done', rows: 214, total: 1932400, columns: [], mapping: {}, errors: [], reviewTasks: [], templateSaved: true },
  { id: 'imp-2', fileName: 'Счёт_YandexCloud_10-2026.pdf', type: 'pdf', vendorId: 'v-yc', uploadedAt: iso(9 * DAY), uploadedBy: 'Игорь Лебедев', status: 'review', rows: 18, total: 151200, columns: [], mapping: {}, errors: [], reviewTasks: [
    { id: 'rt-1', row: 7, field: 'Сумма', value: '7 400,00', confidence: 0.62, resolved: false },
    { id: 'rt-2', row: 12, field: 'Период', value: '01.10–31.1O', confidence: 0.48, resolved: false },
  ], templateSaved: false },
  { id: 'imp-3', fileName: 'Лицензии_Битрикс.xlsx', type: 'excel', vendorId: 'v-b24', uploadedAt: iso(25 * DAY), uploadedBy: 'Мария Соколова', status: 'done', rows: 50, total: 24800, columns: ['ФИО', 'Email', 'Последний вход', 'Тариф'], mapping: { 'ФИО': 'Сотрудник', Email: 'Email', 'Последний вход': 'Последняя активность', 'Тариф': 'Продукт' }, errors: [{ row: 31, message: 'Дата «31.02.2026» не существует' }], reviewTasks: [], templateSaved: true },
  { id: 'imp-4', fileName: 'скан_акт_Kaspersky.jpg', type: 'scan', vendorId: 'v-kasp', uploadedAt: iso(12 * DAY), uploadedBy: 'Игорь Лебедев', status: 'failed', rows: 0, total: 0, columns: [], mapping: {}, errors: [{ row: 0, message: 'Не удалось распознать таблицу: изображение размыто' }], reviewTasks: [], templateSaved: false },
];

// Техника: кто что держит. Номера актов идут по порядку внутри года, как в ядре (АВ-<год>-<n>).
export const COMPANY_NAME = 'ООО «Северный ветер»';

const assets: Asset[] = [
  { id: 'as-01', inventoryNumber: 'INV-0101', model: 'Lenovo ThinkPad T14 Gen 5', type: 'LAPTOP', cost: 125990, serialNumber: 'PF4ABC12', status: 'ASSIGNED', source: 'intune', createdAt: iso(300 * DAY) },
  { id: 'as-02', inventoryNumber: 'INV-0102', model: 'Apple MacBook Pro 14 M3', type: 'LAPTOP', cost: 219990, serialNumber: 'C02XK1Y2MD6T', status: 'ASSIGNED', source: 'jamf', createdAt: iso(280 * DAY) },
  { id: 'as-03', inventoryNumber: 'INV-0103', model: 'Apple MacBook Air 13 M2', type: 'LAPTOP', cost: 109990, serialNumber: 'FVFHK3Q2Q6L4', status: 'ASSIGNED', source: 'jamf', createdAt: iso(260 * DAY) },
  { id: 'as-04', inventoryNumber: 'INV-0104', model: 'Lenovo ThinkPad X1 Carbon Gen 11', type: 'LAPTOP', cost: 189990, serialNumber: 'PF3ZQ8LM', status: 'ASSIGNED', source: 'intune', createdAt: iso(240 * DAY) },
  { id: 'as-05', inventoryNumber: 'INV-0105', model: 'Huawei MateBook D 16', type: 'LAPTOP', cost: 74990, serialNumber: 'HM16D2207731', status: 'ASSIGNED', source: 'manual', createdAt: iso(200 * DAY) },
  { id: 'as-06', inventoryNumber: 'INV-0106', model: 'Lenovo ThinkPad E14 Gen 4', type: 'LAPTOP', cost: 69990, serialNumber: 'PF2MM4KX', status: 'IN_STOCK', source: 'intune', createdAt: iso(400 * DAY) },
  { id: 'as-07', inventoryNumber: 'INV-0107', model: 'Apple MacBook Air 13 M1', type: 'LAPTOP', cost: 84990, serialNumber: 'FVFDL1A9Q6L5', status: 'IN_STOCK', source: 'jamf', createdAt: iso(420 * DAY) },
  { id: 'as-08', inventoryNumber: 'INV-0201', model: 'Apple iPhone 15', type: 'PHONE', cost: 79990, serialNumber: 'F2LXQ0J1N72Q', status: 'ASSIGNED', source: 'jamf', createdAt: iso(150 * DAY) },
  { id: 'as-09', inventoryNumber: 'INV-0202', model: 'Samsung Galaxy S24', type: 'PHONE', cost: 74990, serialNumber: 'R5CX10ABCDE', status: 'ASSIGNED', source: 'intune', createdAt: iso(140 * DAY) },
  { id: 'as-10', inventoryNumber: 'INV-0203', model: 'Apple iPhone 13', type: 'PHONE', cost: 59990, serialNumber: 'DX3GH7P1N72J', status: 'ASSIGNED', source: 'jamf', createdAt: iso(500 * DAY) },
  { id: 'as-11', inventoryNumber: 'INV-0301', model: 'Монитор Dell P2723QE 27″', type: 'MONITOR', cost: 42990, status: 'ASSIGNED', source: 'onec', createdAt: iso(330 * DAY) },
  { id: 'as-12', inventoryNumber: 'INV-0302', model: 'Монитор Dell P2423 24″', type: 'MONITOR', cost: 21990, status: 'IN_STOCK', source: 'onec', createdAt: iso(330 * DAY) },
  { id: 'as-13', inventoryNumber: 'INV-0401', model: 'Гарнитура Jabra Evolve2 65', type: 'OTHER', cost: 24990, status: 'ASSIGNED', source: 'manual', createdAt: iso(90 * DAY) },
  { id: 'as-14', inventoryNumber: 'INV-0099', model: 'HP ProBook 450 G6', type: 'LAPTOP', cost: 15000, serialNumber: '5CD9123XYZ', status: 'RETIRED', source: 'onec', createdAt: iso(1500 * DAY) },
];

const year = new Date().getFullYear();
const act = (n: number) => `АВ-${year}-${String(n).padStart(4, '0')}`;
const day = (msAgo: number) => iso(msAgo).slice(0, 10);
const dayAhead = (ms: number) => isoAhead(ms).slice(0, 10);

const assetAssignments: AssetAssignment[] = [
  // история: со склада ноутбук был у уволенной Беловой и у дизайнера, оба вернули
  { id: 'aa-01', assetId: 'as-06', employeeId: 'e-11', issuedAt: day(390 * DAY), returnDue: null, returnedAt: day(130 * DAY), actNumber: null, issuedBy: 'Мария Соколова' },
  { id: 'aa-02', assetId: 'as-07', employeeId: 'e-13', issuedAt: day(410 * DAY), returnDue: null, returnedAt: day(40 * DAY), actNumber: null, issuedBy: 'Мария Соколова' },
  { id: 'aa-03', assetId: 'as-01', employeeId: 'e-03', issuedAt: day(290 * DAY), returnDue: null, returnedAt: null, actNumber: act(1), issuedBy: 'connector:intune' },
  { id: 'aa-04', assetId: 'as-02', employeeId: 'e-06', issuedAt: day(270 * DAY), returnDue: null, returnedAt: null, actNumber: act(2), issuedBy: 'connector:jamf' },
  { id: 'aa-05', assetId: 'as-11', employeeId: 'e-06', issuedAt: day(260 * DAY), returnDue: null, returnedAt: null, actNumber: act(3), issuedBy: 'Мария Соколова' },
  // уволенный Морозов не сдал ноутбук и телефон
  { id: 'aa-06', assetId: 'as-03', employeeId: 'e-08', issuedAt: day(250 * DAY), returnDue: null, returnedAt: null, actNumber: act(4), issuedBy: 'connector:jamf' },
  { id: 'aa-07', assetId: 'as-10', employeeId: 'e-08', issuedAt: day(245 * DAY), returnDue: null, returnedAt: null, actNumber: act(5), issuedBy: 'Мария Соколова' },
  // у тимлида Волкова второй ноутбук
  { id: 'aa-08', assetId: 'as-04', employeeId: 'e-06', issuedAt: day(230 * DAY), returnDue: null, returnedAt: null, actNumber: act(6), issuedBy: 'connector:intune' },
  { id: 'aa-09', assetId: 'as-08', employeeId: 'e-09', issuedAt: day(140 * DAY), returnDue: null, returnedAt: null, actNumber: act(7), issuedBy: 'connector:jamf' },
  { id: 'aa-10', assetId: 'as-09', employeeId: 'e-10', issuedAt: day(130 * DAY), returnDue: null, returnedAt: null, actNumber: act(8), issuedBy: 'Максим Фёдоров' },
  // временная выдача, срок прошёл
  { id: 'aa-11', assetId: 'as-05', employeeId: 'e-14', issuedAt: day(80 * DAY), returnDue: day(10 * DAY), returnedAt: null, actNumber: act(9), issuedBy: 'Максим Фёдоров' },
  { id: 'aa-12', assetId: 'as-13', employeeId: 'e-15', issuedAt: day(60 * DAY), returnDue: dayAhead(120 * DAY), returnedAt: null, actNumber: act(10), issuedBy: 'Максим Фёдоров' },
];

const notifications: Notification[] = [
  { id: 'n-1', at: iso(3 * 3600000), text: 'Агент «Исследователь рынка» остановлен детектором: риск 91', link: '/agents/a-research', level: 'critical', read: false },
  { id: 'n-2', at: iso(40 * 60000), text: 'Новое согласование: «Агент лидогенерации», 2 400 ₽', link: '/approvals', level: 'warning', read: false },
  { id: 'n-3', at: iso(1 * DAY), text: 'Новая находка: ИИ-расходы мимо шлюза', link: '/findings/f-5', level: 'warning', read: false },
  { id: 'n-4', at: iso(2 * DAY), text: 'Импорт «Выписка_октябрь_2026.txt» завершён: 214 строк', link: '/imports/imp-1', level: 'good', read: true },
  { id: 'n-5', at: iso(4 * DAY), text: 'Бюджет отдела «Маркетинг» израсходован на 82%', link: '/budgets', level: 'warning', read: true },
];

const reports: Report[] = [
  { id: 'r-1', name: 'Расходы и экономия за октябрь 2026', format: 'xlsx', createdAt: iso(5 * DAY), period: '2026-10', createdBy: 'Игорь Лебедев' },
  { id: 'r-2', name: 'Отчёт для собственника, III квартал', format: 'pdf', createdAt: iso(35 * DAY), period: '2026-Q3', createdBy: 'Анна Ковалёва' },
];

const costHistory: CostPoint[] = [
  ['2025-12', 612000, 118000], ['2026-01', 640000, 131000], ['2026-02', 628000, 149000], ['2026-03', 655000, 176000],
  ['2026-04', 661000, 192000], ['2026-05', 668000, 214000], ['2026-06', 659000, 239000], ['2026-07', 672000, 268000],
  ['2026-08', 676000, 291000], ['2026-09', 681000, 322000], ['2026-10', 664000, 356000],
].map(([month, software, agentsCost]) => ({ month: month as string, software: software as number, agents: agentsCost as number }));

// ——— Подключённые системы (ADR 0019) ———
// GET /api/v1/connections/types: то же, что SystemTypes.java в ядре
export const SYSTEM_TYPES: SystemType[] = [
  { type: 'onec', title: '1С (OData)', kind: 'accounting', required: ['url', 'user'], optional: [], secrets: ['ONEC_PASSWORD'] },
  { type: 'moysklad', title: 'МойСклад', kind: 'accounting', required: [], optional: ['url'], secrets: ['MOYSKLAD_TOKEN'] },
  { type: 'merge', title: 'Merge (учёт и кадры)', kind: 'accounting', required: [], optional: ['url'], secrets: ['MERGE_API_KEY', 'MERGE_ACCOUNTING_TOKEN', 'MERGE_HRIS_TOKEN'] },
  { type: 'bitrix24', title: 'Битрикс24', kind: 'hr', required: [], optional: [], secrets: ['BITRIX24_WEBHOOK'] },
  { type: 'yandex360', title: 'Яндекс 360', kind: 'identity', required: ['org_id'], optional: ['url'], secrets: ['YANDEX360_TOKEN'] },
  { type: 'google_workspace', title: 'Google Workspace', kind: 'identity', required: ['admin'], optional: ['customer', 'ignore'], secrets: ['GOOGLE_WORKSPACE_KEY'] },
  { type: 'okta', title: 'Okta', kind: 'identity', required: ['url'], optional: ['ignore'], secrets: ['OKTA_TOKEN'] },
  { type: 'entra', title: 'Microsoft Entra ID', kind: 'identity', required: ['directory_id', 'client_id'], optional: ['ignore'], secrets: ['ENTRA_CLIENT_SECRET'] },
  { type: 'jamf', title: 'Jamf Pro', kind: 'devices', required: ['url', 'client_id'], optional: [], secrets: ['JAMF_CLIENT_SECRET'] },
  { type: 'intune', title: 'Microsoft Intune', kind: 'devices', required: ['directory_id', 'client_id'], optional: [], secrets: ['INTUNE_CLIENT_SECRET'] },
  { type: 'tbank', title: 'Т-Банк', kind: 'bank', required: [], optional: ['accounts', 'url'], secrets: ['TBANK_TOKEN'] },
  { type: 'mail', title: 'Почтовый ящик со счетами', kind: 'mail', required: ['host', 'user'], optional: ['folder', 'min_image_kb', 'min_image_side'], secrets: ['MAIL_PASSWORD', 'CORE_TOKEN'] },
];

const HOUR = 3600000;
// Все статусы для демо: 1С работает, Okta с ошибкой, Т-Банк ждёт первой выгрузки, Jamf отключён,
// МойСклад настроен только в файле агента, Битрикс24 ждёт запрошенной синхронизации.
const connections: Connection[] = [
  { id: 'c-onec', type: 'onec', name: '1С: Бухгалтерия', status: 'connected', enabled: true, managedInCore: true, settings: { url: 'https://1c.severveter.ru/odata', user: 'costguard' }, lastSyncAt: iso(0.4 * HOUR), recordsLastSync: 120, recordsTotal: 4380, lastError: 'Read timed out', lastErrorAt: iso(9 * DAY), createdAt: iso(60 * DAY) },
  { id: 'c-okta', type: 'okta', name: 'Okta', status: 'failing', enabled: true, managedInCore: true, settings: { url: 'https://severveter.okta.com' }, lastSyncAt: iso(26 * HOUR), recordsLastSync: 54, recordsTotal: 1210, lastError: '401 Unauthorized\nGET https://severveter.okta.com/api/v1/users\nТокен отозван или истёк', lastErrorAt: iso(0.9 * HOUR), createdAt: iso(40 * DAY) },
  { id: 'c-tbank', type: 'tbank', name: 'Т-Банк', status: 'pending', enabled: true, managedInCore: true, settings: { accounts: '40702810910000012345' }, recordsTotal: 0, lastError: 'Missing setting or secret TBANK_TOKEN', lastErrorAt: iso(0.2 * HOUR), createdAt: iso(1 * DAY) },
  { id: 'c-jamf', type: 'jamf', name: 'Jamf Pro', status: 'disconnected', enabled: false, managedInCore: true, settings: { url: 'https://severveter.jamfcloud.com', client_id: 'cg-reader' }, lastSyncAt: iso(20 * DAY), recordsLastSync: 31, recordsTotal: 310, createdAt: iso(90 * DAY) },
  { id: 'c-moysklad', type: 'moysklad', name: 'МойСклад', status: 'connected', enabled: true, managedInCore: false, settings: {}, lastSyncAt: iso(2 * HOUR), recordsLastSync: 18, recordsTotal: 640, createdAt: iso(15 * DAY) },
  { id: 'c-bitrix', type: 'bitrix24', name: 'Битрикс24', status: 'sync_requested', enabled: true, managedInCore: true, settings: {}, lastSyncAt: iso(5 * HOUR), recordsLastSync: 16, recordsTotal: 96, syncRequestedAt: iso(0.05 * HOUR), createdAt: iso(10 * DAY) },
];

function makeRuns(): ConnectionRun[] {
  const out: ConnectionRun[] = [];
  connections.filter((c) => c.lastSyncAt).forEach((c) => {
    for (let i = 0; i < 6; i++) {
      const started = new Date(new Date(c.lastSyncAt!).getTime() - i * HOUR);
      const failed = c.status === 'failing' && i === 0;
      out.push({
        id: `run-${c.id}-${i}`, connectionId: c.id, startedAt: started.toISOString(),
        finishedAt: new Date(started.getTime() + (20 + i * 7) * 1000).toISOString(),
        status: failed ? 'FAILED' : 'SUCCEEDED', records: failed ? 0 : (c.recordsLastSync ?? 0) - i * 2,
        error: failed ? c.lastError : undefined,
      });
    }
  });
  return out;
}

export function createSeed(): AppState {
  return structuredClone({
    users, departments, employees, agents, keys, mandates, budgets, operations, approvals,
    vendors, contracts, subscriptions, assignments: makeAssignments(), assets, assetAssignments, findings, imports,
    notifications, reports, costHistory, connections, connectionRuns: makeRuns(),
  });
}
