import { afterEach, describe, expect, it } from 'vitest';
import { translateSync } from './i18n';
import { currencySign, fmtMoney, setCompanyCurrency, toCsv } from './lib';

describe('fmtMoney', () => {
  afterEach(() => setCompanyCurrency('RUB'));

  it('пишет сумму в валюте компании, а не всегда в рублях (ADR 0072)', () => {
    expect(fmtMoney(1500)).toMatch(/^1\s500\s₽$/);
    setCompanyCurrency('USD');
    expect(fmtMoney(1500)).toMatch(/^1\s500\s\$$/);
    expect(fmtMoney(12.5)).toMatch(/^12,50\s\$$/);
  });

  it('подписывает поля знаком валюты компании', () => {
    expect(currencySign()).toBe('₽');
    setCompanyCurrency('USD');
    expect(currencySign()).toBe('$');
    expect(currencySign('XYZ')).toBe('XYZ');
  });

  it('переводит подписи с любой валютой на английский', () => {
    expect(translateSync('Бюджет на месяц, $')).toBe('Monthly budget, $');
    expect(translateSync('Лимит на месяц, ₽')).toBe('Monthly limit, ₽');
  });

  it('берёт названную валюту и не принимает то, что не код', () => {
    expect(fmtMoney(10, 'EUR')).toMatch(/^10\s€$/);
    setCompanyCurrency('доллар');
    expect(fmtMoney(1)).toContain('₽');
    expect(fmtMoney(3, 'XYZ')).toMatch(/3\sXYZ/);
  });
});

describe('toCsv', () => {
  it('не даёт тексту стать формулой в Excel', () => {
    const csv = toCsv([['=HYPERLINK("http://x","y")', '+1', '-2', '@SUM(A1)', '\tx', 'Zoom', -5, 12.5]]);
    const cells = csv.replace('﻿', '').split(';');
    expect(cells[0]).toBe(`"'=HYPERLINK(""http://x"",""y"")"`);
    expect(cells.slice(1, 5)).toEqual(["'+1", "'-2", "'@SUM(A1)", "'\tx"]);
    // обычный текст и числа (в том числе отрицательные) не меняются
    expect(cells.slice(5)).toEqual(['Zoom', '-5', '12.5']);
  });

  it('берёт в кавычки разделитель и перевод строки', () => {
    expect(toCsv([['a;b', 'c\nd']]).replace('﻿', '')).toBe('"a;b";"c\nd"');
  });
});
