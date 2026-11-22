// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { fmtDate, fmtMoney, setCompanyCurrency, uiLocale } from '../lib';
import { setLang } from './index';

// ADR 0074: in English, numbers, dates and money are written as English speakers write them.
describe('formats follow the UI language', () => {
  afterEach(() => {
    setLang('ru');
    setCompanyCurrency('RUB');
  });

  it('writes dollars and dates the English way in English', () => {
    setLang('en');
    setCompanyCurrency('USD');
    expect(uiLocale()).toMatch(/^en/);
    expect(fmtMoney(1500)).toBe('$1,500');
    expect(fmtDate('2026-11-22')).toMatch(/Nov/);
  });

  it('keeps the Russian way in Russian', () => {
    setLang('ru');
    expect(uiLocale()).toBe('ru-RU');
    expect(fmtMoney(1500)).toMatch(/^1\s500\s₽$/);
  });
});
