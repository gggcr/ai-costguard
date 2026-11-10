// Fields a column of an import maps to (FocusFields.ALL of the core imports module).
export const FOCUS_FIELDS = ['Поставщик', 'Продукт', 'Сотрудник', 'Email', 'Сумма', 'Валюта', 'Период начала', 'Период окончания', 'Последняя активность', 'Отдел', 'Инвентарный номер', 'Серийный номер', 'Модель техники', 'Тип техники', '— не импортировать —'];
/** The same fields as the core names them (FocusFields.ALL): the screen shows the label, the API takes the id. */
export const FOCUS_FIELD_IDS: Record<string, string> = {
  'Поставщик': 'vendor', 'Продукт': 'product', 'Сотрудник': 'employee', 'Email': 'email', 'Сумма': 'amount', 'Валюта': 'currency',
  'Период начала': 'period_start', 'Период окончания': 'period_end', 'Последняя активность': 'last_active', 'Отдел': 'department',
  'Инвентарный номер': 'inventory_number', 'Серийный номер': 'serial_number', 'Модель техники': 'asset_model', 'Тип техники': 'asset_type',
  '— не импортировать —': 'skip',
};
export const focusLabels = (mapping: Record<string, string>) => Object.fromEntries(Object.entries(mapping).map(([c, id]) =>
  [c, Object.keys(FOCUS_FIELD_IDS).find((label) => FOCUS_FIELD_IDS[label] === id) ?? id]));
export const focusIds = (mapping: Record<string, string>) => Object.fromEntries(Object.entries(mapping).map(([c, label]) =>
  [c, FOCUS_FIELD_IDS[label] ?? label]));
