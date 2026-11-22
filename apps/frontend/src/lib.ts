// Общие утилиты: форматирование, идентификаторы, фейковые хэши для квитанций.

import { getLang } from './i18n';

/**
 * The locale numbers, dates and money are written in: the UI language (ADR 0074). In English, the browser's own
 * English (en-GB writes 22 Nov 2026, en-US Nov 22, 2026); en-US when it has none.
 */
export function uiLocale(): string {
  if (getLang() !== 'en') return 'ru-RU';
  const langs = typeof navigator === 'undefined' ? [] : (navigator.languages ?? [navigator.language]);
  return langs.find((l) => l?.toLowerCase().startsWith('en')) ?? 'en-US';
}


// Деньги компании — в её валюте (ADR 0072): бюджеты, цены подписок, находки и траты агентов ядро держит в валюте
// компании, а не в рублях. Хранилище данных сообщает её, как только прочитает /api/v1/company.
let companyCurrency = 'RUB';
const formats = new Map<string, Intl.NumberFormat>();

export function setCompanyCurrency(code: string | null | undefined) {
  if (code && /^[A-Z]{3}$/.test(code)) companyCurrency = code;
}

export const currentCurrency = () => companyCurrency;

function moneyFormat(currency: string, digits: number): Intl.NumberFormat {
  const key = `${uiLocale()}:${currency}:${digits}`;
  let f = formats.get(key);
  if (!f) {
    try {
      f = new Intl.NumberFormat(uiLocale(), { style: 'currency', currency, maximumFractionDigits: digits });
    } catch {
      f = new Intl.NumberFormat(uiLocale(), { maximumFractionDigits: digits }); // код, которого браузер не знает
    }
    formats.set(key, f);
  }
  return f;
}

/** Знак валюты компании для подписей полей и столбцов: ₽, $, € (код — если браузер знака не знает). */
export function currencySign(currency: string = companyCurrency): string {
  const f = moneyFormat(currency, 0);
  return f.formatToParts(0).find((p) => p.type === 'currency')?.value ?? currency;
}

/** Сумма в валюте компании или в названной: «1 500 ₽», «1 500 $». Мелочь до сотни — с копейками/центами. */
export function fmtMoney(v: number, currency: string = companyCurrency): string {
  const f = moneyFormat(currency, Math.abs(v) < 100 && v % 1 !== 0 ? 2 : 0);
  const text = f.format(v);
  return f.resolvedOptions().style === 'currency' ? text : `${text} ${currency}`;
}
export const fmtNum = (v: number) => new Intl.NumberFormat(uiLocale()).format(v);
export const fmtPct = (v: number) => `${Math.round(v)}%`;

export const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString(uiLocale(), { day: '2-digit', month: 'short', year: 'numeric' }) : '—';

export const fmtDateTime = (iso: string) =>
  new Date(iso).toLocaleString(uiLocale(), { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

export const fmtMonth = (ym: string) => {
  const [y, m] = ym.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(uiLocale(), { month: 'short', year: '2-digit' });
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
    // Текст, который Excel принял бы за формулу (=, +, -, @, таб, возврат каретки в начале), начинается с апострофа:
    // название подписки «=HYPERLINK(...)» в выгрузке не должно выполниться у того, кто её откроет
    const s = typeof v === 'string' && /^[=+\-@\t\r]/.test(v) ? `'${v}` : String(v);
    return /[";\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  // Точка с запятой и BOM: так Excel в русской локали откроет файл без мастера импорта
  return '﻿' + rows.map((r) => r.map(esc).join(';')).join('\n');
}
