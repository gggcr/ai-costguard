// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { setLang, translateSync } from './index';

describe('i18n', () => {
  it('translates dictionary strings, templates and dates', () => {
    expect(translateSync('Находки')).toBe('Findings');
    expect(translateSync('  Выйти ')).toBe('  Sign out ');
    expect(translateSync('4 подписок')).toBe('4 subscriptions');
    expect(translateSync('Агент «Бот закупок» создан')).toBe('Agent «Бот закупок» created');
    expect(translateSync('14 нояб. 2026 г.')).toBe('Nov 14, 2026');
    expect(translateSync('ноябрь 2026 г.')).toBe('November 2026');
    expect(translateSync('API key 42')).toBe('API key 42');
  });

  it('returns null for free text it cannot translate without the browser translator', () => {
    expect(translateSync('Приказ о премировании отдела продаж')).toBeNull();
  });

  it('translates new DOM text in EN mode and restores it in RU mode', async () => {
    document.body.innerHTML = '<div id="a">Находки</div>';
    setLang('en');
    expect(document.getElementById('a')!.textContent).toBe('Findings');
    const p = document.createElement('p');
    p.textContent = 'Выйти';
    p.setAttribute('placeholder', 'Поиск');
    document.body.appendChild(p);
    await new Promise((r) => setTimeout(r, 0));
    expect(p.textContent).toBe('Sign out');
    expect(p.getAttribute('placeholder')).toBe('Search');
    setLang('ru');
    expect(document.getElementById('a')!.textContent).toBe('Находки');
    expect(p.textContent).toBe('Выйти');
  });
});
