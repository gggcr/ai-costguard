// RU/EN switch for the whole interface, including text that appears later (server data, what users type in).
//
// The interface is written in Russian. In English mode a MutationObserver translates every text node and the
// placeholder/title/aria-label/alt attributes as they appear:
//   1. the dictionary en.json (strings of the code, exact and templates with {0}…), instant;
//   2. dates and month names;
//   3. anything else with Cyrillic (finding titles from the core, documents an employer added, names of
//      subscriptions…) — the browser's built-in on-device translator (Translator API, Chrome 138+), cached.
// The original text is remembered, so switching back to RU restores it exactly. Elements marked
// data-no-translate (and inputs, textareas, code) are never touched.
import dict from './en.json';

export type Lang = 'ru' | 'en';
const KEY = 'costguard.lang';
const CACHE_KEY = 'costguard.mt.v1';
const CY = /[А-Яа-яЁё]/;
const ATTRS = ['placeholder', 'title', 'aria-label', 'alt'] as const;

// Texts the core builds (finding titles and the like) and common data of a Russian company.
const SERVER_TEMPLATES: Record<string, string> = {
  'Свободные места: {0}': 'Free seats: {0}',
  'Две подписки на {0}': 'Two subscriptions to {0}',
  'Пересекающиеся инструменты: {0} и {1}': 'Overlapping tools: {0} and {1}',
  'Договор продлится сам: {0} № {1}': 'Contract renews automatically: {0} No. {1}',
  'Лицензия уволенного: {0}': 'License of a leaver: {0}',
  'Лицензия не используется: {0}': 'License not used: {0}',
  'Техника простаивает: {0}': 'Hardware idle: {0}',
  'Уволенный не сдал технику: {0}': 'Leaver did not return hardware: {0}',
  'Срок возврата техники прошёл: {0}': 'Hardware return date passed: {0}',
  'Лишняя техника у сотрудника: {0}': 'Extra hardware with an employee: {0}',
  'Избыточный тариф: {0}': 'Excessive plan: {0}',
  'Помесячно вместо годового: {0}': 'Monthly instead of annual: {0}',
  'Теневой SaaS: {0}': 'Shadow SaaS: {0}',
  '{0} разово': '{0} one-off',
  'Новая находка: {0}, {1}/мес': 'New finding: {0}, {1}/mo',
  'Новая находка: {0}': 'New finding: {0}',
  '{0} (инв. № {1})': '{0} (inv. # {1})',
  'Оплачивать {0} мест вместо {1}': 'Pay for {0} seats instead of {1}',
};
const DATA: Record<string, string> = {
  'Демо Владелец': 'Demo Owner', 'Демо': 'Demo', 'Владелец': 'Owner',
  'ИТ': 'IT', 'Продажи': 'Sales', 'Бухгалтерия': 'Accounting', 'Маркетинг': 'Marketing', 'Финансы': 'Finance',
  'Разработка': 'Development', 'Поддержка': 'Support', 'Руководство': 'Management', 'Склад': 'Warehouse',
  'Руководитель ИТ': 'Head of IT', 'Главный бухгалтер': 'Chief accountant', 'Менеджер': 'Manager',
  'Разработчик': 'Developer', 'Бухгалтер': 'Accountant', 'Генеральный директор': 'CEO', 'Финансовый директор': 'CFO',
  'Атлассиан': 'Atlassian', 'Яндекс': 'Yandex', 'Яндекс 360': 'Yandex 360', 'Битрикс24': 'Bitrix24', 'Сбер': 'Sber',
  'Касперский': 'Kaspersky', 'Контур': 'Kontur', 'Мой Офис': 'MyOffice', 'МойОфис': 'MyOffice', 'Тинькофф': 'Tinkoff',
  'Т-Банк': 'T-Bank', 'Ноутбук': 'Laptop', 'Монитор': 'Monitor', 'Телефон': 'Phone',
  'ООО «Ромашка»': 'Romashka LLC',
};
const TR: Record<string, string> = { а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'yo', ж: 'zh', з: 'z', и: 'i', й: 'y', к: 'k',
  л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch',
  ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya' };
const translit = (w: string) => w.replace(/[А-Яа-яЁё]/g, (ch) => {
  const low = ch.toLowerCase(), t = TR[low] ?? ch;
  return ch === low ? t : t.charAt(0).toUpperCase() + t.slice(1);
});
/** Names and short data: every Cyrillic word known in DATA or capitalised (a name, a brand) → word by word. */
function wordwise(s: string): string | null {
  const words = s.split(' ');
  if (words.length > 4) return null;
  const out: string[] = [];
  for (const w of words) {
    const m = w.match(/^([«"(]*)(.*?)([»".,:;)]*)$/)!;
    const core = m[2];
    if (!CY.test(core)) { out.push(w); continue; }
    if (DATA[core] !== undefined) { out.push(m[1] + DATA[core] + m[3]); continue; }
    if (/^[А-ЯЁ]/.test(core)) { out.push(m[1] + translit(core) + m[3]); continue; }
    return null;
  }
  return out.join(' ');
}

const exact = new Map<string, string>([
  ...Object.entries((dict as { exact: Record<string, string> }).exact), ...Object.entries(DATA)]);
interface Pattern { re: RegExp; en: string; loose: boolean; quoted: boolean; }
const cyWords = (ru: string) => (ru.replace(/\{\d+\}/g, ' ').match(/[А-Яа-яЁё]+/g) ?? []).length;
const patterns: Pattern[] = Object.entries({ ...(dict as { templates: Record<string, string> }).templates, ...SERVER_TEMPLATES })
  .filter(([ru]) => ru.replace(/\{\d+\}/g, '').replace(/[\s·,.:;()«»—-]/g, '').length > 0)
  .sort((a, b) => b[0].replace(/\{\d+\}/g, '').length - a[0].replace(/\{\d+\}/g, '').length)
  .map(([ru, en]) => ({
    re: new RegExp('^' + ru.split(/(\{\d+\})/).map((part) => /^\{\d+\}$/.test(part) ? '([\\s\\S]+?)'
      : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('') + '$'),
    en,
    // A short template («Договор {0}») must not swallow a whole Russian sentence it was never meant for.
    loose: cyWords(ru) >= 2 || /«\{\d+\}»/.test(ru),
    quoted: /«\{\d+\}»/.test(ru),
  }));

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
const MONTHS_SHORT = ['янв', 'февр', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сент', 'окт', 'нояб', 'дек'];
const MONTHS_NOM = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];
const EN_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const EN_SHORT = EN_MONTHS.map((m) => m.slice(0, 3));

function dates(s: string): string {
  return s
    .replace(/(\d{1,2}) ([а-я]+)\.? (\d{4})(?: г\.)?/g, (m, d, mon, y) => {
      const i = MONTHS_GEN.indexOf(mon) >= 0 ? MONTHS_GEN.indexOf(mon) : MONTHS_SHORT.indexOf(mon);
      return i >= 0 ? `${EN_SHORT[i]} ${d}, ${y}` : m;
    })
    .replace(/(\d{1,2}) ([а-я]+)\.?(?= |$|,)/g, (m, d, mon) => {
      const i = MONTHS_GEN.indexOf(mon) >= 0 ? MONTHS_GEN.indexOf(mon) : MONTHS_SHORT.indexOf(mon);
      return i >= 0 ? `${EN_SHORT[i]} ${d}` : m;
    })
    .replace(/([а-я]+) (\d{4})(?: г\.)?/gi, (m, mon, y) => {
      const i = MONTHS_NOM.indexOf(mon.toLowerCase());
      return i >= 0 ? `${EN_MONTHS[i]} ${y}` : m;
    })
    .replace(/(\d{4}) г\./g, '$1');
}

// ---- machine translation of what the dictionary does not know --------------------------------------------
type TranslatorLike = { translate(text: string): Promise<string> };
let translator: TranslatorLike | null = null;
let translatorTried = false;
const mtCache = new Map<string, string>();
const pending = new Map<string, Promise<string | null>>();
try {
  const saved = JSON.parse(localStorage.getItem(CACHE_KEY) || '{}') as Record<string, string>;
  Object.entries(saved).forEach(([k, v]) => mtCache.set(k, v));
} catch { /* no storage */ }
let saveTimer: number | undefined;
function persist() {
  window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    try {
      const entries = [...mtCache.entries()].slice(-3000);
      localStorage.setItem(CACHE_KEY, JSON.stringify(Object.fromEntries(entries)));
    } catch { /* full or blocked */ }
  }, 1000);
}

/** Creates the on-device translator. Call from a click: the first download of the model needs a user gesture. */
export async function prepareTranslator(): Promise<boolean> {
  if (translator) return true;
  if (translatorTried) return false;
  translatorTried = true;
  const api = (globalThis as unknown as { Translator?: {
    availability(o: object): Promise<string>; create(o: object): Promise<TranslatorLike>; } }).Translator;
  if (!api) return false;
  try {
    const opts = { sourceLanguage: 'ru', targetLanguage: 'en' };
    if ((await api.availability(opts)) === 'unavailable') return false;
    translator = await api.create(opts);
    return true;
  } catch { return false; }
}

function machine(text: string): Promise<string | null> {
  const hit = mtCache.get(text);
  if (hit !== undefined) return Promise.resolve(hit);
  if (!translator) return Promise.resolve(null);
  let p = pending.get(text);
  if (!p) {
    p = translator.translate(text).then((en) => { mtCache.set(text, en); persist(); return en; }).catch(() => null)
      .finally(() => pending.delete(text));
    pending.set(text, p);
  }
  return p;
}

/** Synchronous translation: dictionary, templates, dates, cached machine translations. null = not known yet. */
export function translateSync(text: string): string | null {
  const lead = text.match(/^\s*/)![0], trail = text.match(/\s*$/)![0];
  const core = text.trim().replace(/\s+/g, ' ');
  if (!core || !CY.test(core)) return text;
  const done = (en: string) => lead + en + trail;
  const e = exact.get(core);
  if (e !== undefined) return done(e);
  for (const { re, en, loose, quoted } of patterns) {
    const m = core.match(re);
    if (!m) continue;
    const parts = m.slice(1).map((x) => (CY.test(x) ? translateSync(x) ?? wordwise(x) : x));
    // An untranslated part may stay as is only in a loose template, and only if it looks like a name (no word
    // starts with a lowercase Cyrillic letter), unless the template quotes it: «{0}».
    const nameLike = (x: string) => !/(^|[\s(·,:;—-])[а-яё]/.test(x.trim());
    if (m.slice(1).some((x, i) => parts[i] === null && !(loose && (quoted || nameLike(x))))) continue;
    // Names and other data inside a template stay as they are when nothing knows them.
    return done(en.replace(/\{(\d+)\}/g, (_, i) => parts[Number(i)] ?? m[Number(i) + 1]));
  }
  const d = dates(core);
  if (!CY.test(d)) return done(d);
  const c = mtCache.get(d);
  if (c !== undefined) return done(c);
  const w = wordwise(d);
  if (w !== null && !translator) return done(w);
  return null;
}

async function translateAsync(text: string): Promise<string | null> {
  const sync = translateSync(text);
  if (sync !== null) return sync;
  const lead = text.match(/^\s*/)![0], trail = text.match(/\s*$/)![0];
  const en = await machine(dates(text.trim().replace(/\s+/g, ' ')));
  return en === null ? null : lead + en + trail;
}

// ---- DOM ---------------------------------------------------------------------------------------------------
let lang: Lang = (() => { try { return localStorage.getItem(KEY) === 'en' ? 'en' : 'ru'; } catch { return 'ru'; } })();
const originalText = new WeakMap<Text, string>();
const shownText = new WeakMap<Text, string>();
const originalAttr = new WeakMap<Element, Map<string, string>>();
const listeners = new Set<(l: Lang) => void>();
let observer: MutationObserver | null = null;

const skip = (el: Element | null): boolean =>
  !!el?.closest('[data-no-translate],script,style,code,pre,textarea,[contenteditable="true"]');

function doText(node: Text) {
  if (skip(node.parentElement)) return;
  const current = node.data;
  // React may have replaced the text since we translated it: then the new text is the original.
  if (shownText.get(node) !== current) originalText.set(node, current);
  const ru = originalText.get(node)!;
  if (!CY.test(ru)) return;
  const apply = (en: string) => {
    if (lang !== 'en' || originalText.get(node) !== ru) return;
    if (node.data !== en) { shownText.set(node, en); node.data = en; }
  };
  const sync = translateSync(ru);
  if (sync !== null) apply(sync);
  else void translateAsync(ru).then((en) => { if (en !== null) apply(en); });
}

function doAttrs(el: Element) {
  if (skip(el)) return;
  for (const a of ATTRS) {
    const v = el.getAttribute(a);
    if (v === null) continue;
    let map = originalAttr.get(el);
    if (!map) { map = new Map(); originalAttr.set(el, map); }
    const prevShown = map.get('shown:' + a);
    if (prevShown !== v) map.set(a, v);
    const ru = map.get(a)!;
    if (!CY.test(ru)) continue;
    const set = (en: string) => {
      if (lang !== 'en' || map!.get(a) !== ru) return;
      map!.set('shown:' + a, en);
      if (el.getAttribute(a) !== en) el.setAttribute(a, en); // the same value again would loop the observer
    };
    const sync = translateSync(ru);
    if (sync !== null) set(sync); else void translateAsync(ru).then((en) => { if (en !== null) set(en); });
  }
}

function walk(root: Node) {
  if (root.nodeType === Node.TEXT_NODE) { doText(root as Text); return; }
  if (root.nodeType !== Node.ELEMENT_NODE) return;
  const el = root as Element;
  if (skip(el)) return;
  doAttrs(el);
  const tw = document.createTreeWalker(el, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  for (let n = tw.nextNode(); n; n = tw.nextNode()) {
    if (n.nodeType === Node.TEXT_NODE) doText(n as Text); else doAttrs(n as Element);
  }
}

function restore(root: Node) {
  const tw = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  for (let n: Node | null = root; n; n = tw.nextNode()) {
    if (n.nodeType === Node.TEXT_NODE) {
      const t = n as Text, ru = originalText.get(t);
      if (ru !== undefined && shownText.get(t) === t.data) { t.data = ru; shownText.delete(t); }
    } else if (n.nodeType === Node.ELEMENT_NODE) {
      const map = originalAttr.get(n as Element);
      if (map) for (const a of ATTRS) {
        const ru = map.get(a);
        if (ru !== undefined && map.get('shown:' + a) === (n as Element).getAttribute(a)) { (n as Element).setAttribute(a, ru); map.delete('shown:' + a); }
      }
    }
  }
}

function start() {
  if (observer) return;
  walk(document.body);
  document.title = translateSync(document.title) ?? document.title;
  observer = new MutationObserver((records) => {
    for (const r of records) {
      if (r.type === 'characterData') doText(r.target as Text);
      else if (r.type === 'attributes') doAttrs(r.target as Element);
      else r.addedNodes.forEach(walk);
    }
  });
  observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: [...ATTRS] });
}

function stop() {
  observer?.disconnect();
  observer = null;
  restore(document.body);
}

export function getLang(): Lang { return lang; }

export function setLang(next: Lang) {
  lang = next;
  try { localStorage.setItem(KEY, next); } catch { /* ignore */ }
  document.documentElement.lang = next;
  if (next === 'en') { void prepareTranslator().then((ok) => { if (ok) walk(document.body); }); start(); } else stop();
  listeners.forEach((f) => f(next));
}

export function onLang(f: (l: Lang) => void): () => void { listeners.add(f); return () => listeners.delete(f); }

/** Called once at startup: applies the saved language. */
export function initLang() {
  document.documentElement.lang = lang;
  if (lang === 'en') { start(); void prepareTranslator().then((ok) => { if (ok) walk(document.body); }); }
}
