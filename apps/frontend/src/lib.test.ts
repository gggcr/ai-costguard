import { describe, expect, it } from 'vitest';
import { toCsv } from './lib';

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
