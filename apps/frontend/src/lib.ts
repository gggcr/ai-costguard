// Общие утилиты: форматирование, идентификаторы, фейковые хэши для квитанций.

const rub = new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 0 });
const rubPrecise = new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 2 });
const num = new Intl.NumberFormat('ru-RU');

export const fmtRub = (v: number) => (Math.abs(v) < 100 && v % 1 !== 0 ? rubPrecise.format(v) : rub.format(v));
export const fmtNum = (v: number) => num.format(v);
export const fmtPct = (v: number) => `${Math.round(v)}%`;

export const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';

export const fmtDateTime = (iso: string) =>
  new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

export const fmtMonth = (ym: string) => {
  const [y, m] = ym.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'short', year: '2-digit' });
};

export function fmtRelative(iso: string): string {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 0) {
    const ahead = -diff;
    if (ahead < 3600) return `через ${Math.max(1, Math.round(ahead / 60))} мин`;
    if (ahead < 86400) return `через ${Math.round(ahead / 3600)} ч`;
    return `через ${Math.round(ahead / 86400)} дн`;
  }
  if (diff < 60) return 'только что';
  if (diff < 3600) return `${Math.round(diff / 60)} мин назад`;
  if (diff < 86400) return `${Math.round(diff / 3600)} ч назад`;
  return `${Math.round(diff / 86400)} дн назад`;
}

// Календарный день по часам пользователя: в Москве в 01:00 уже завтра, а toISOString ещё вчера
export const localDay = (d = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export const daysUntil = (iso: string) => Math.ceil((new Date(iso).getTime() - Date.now()) / 86400000);

let counter = 0;
export function newId(prefix: string): string {
  counter += 1;
  return `${prefix}-${Date.now().toString(36)}${counter.toString(36)}`;
}

// Детерминированный «хэш» для демо-квитанций. Это не криптография.
export function fakeHash(seed: string, len = 64): string {
  let h = 0x811c9dc5;
  let out = '';
  let i = 0;
  while (out.length < len) {
    const ch = seed.charCodeAt(i % Math.max(seed.length, 1)) || 0;
    h = Math.imul(h ^ (ch + i), 16777619) >>> 0;
    out += (h & 0xff).toString(16).padStart(2, '0');
    i += 1;
  }
  return out.slice(0, len);
}

export const short = (h: string, n = 10) => (h.length > n * 2 ? `${h.slice(0, n)}…${h.slice(-4)}` : h);

// Псевдослучайный генератор для воспроизводимых демо-данных
export function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export function downloadText(fileName: string, content: string, mime = 'text/plain') {
  const blob = new Blob([content], { type: `${mime};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function toCsv(rows: (string | number)[][]): string {
  const esc = (v: string | number) => {
    const s = String(v);
    return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  // Точка с запятой и BOM: так Excel в русской локали откроет файл без мастера импорта
  return '﻿' + rows.map((r) => r.map(esc).join(';')).join('\n');
}
