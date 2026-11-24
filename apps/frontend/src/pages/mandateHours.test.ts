import { describe, expect, it } from 'vitest';
import en from '../i18n/en.json';
import { HOURS, hoursLabel } from './Policies';

// The API answers mandate hours in codes (24/7, workdays); the page shows a Russian label the dictionary translates.
describe('mandate hours', () => {
  it('shows the codes of the API as labels in both languages', () => {
    expect(hoursLabel('24/7')).toBe('круглосуточно');
    expect(hoursLabel('workdays')).toBe('только рабочие дни');
    expect(hoursLabel('09:00–18:00')).toBe('09:00–18:00');
    const exact = (en as { exact: Record<string, string> }).exact;
    expect(exact[hoursLabel('24/7')]).toBe('24/7');
    expect(exact[hoursLabel('workdays')]).toBe('working days only');
  });

  it('keeps the Russian names older answers carry', () => {
    expect(hoursLabel('круглосуточно')).toBe('круглосуточно');
  });

  it('offers only values the API accepts', () => {
    const accepted = /^(24\/7|workdays|([01]\d|2[0-3]):[0-5]\d[–-]([01]\d|2[0-3]):[0-5]\d)$/;
    HOURS.forEach((h) => expect(h).toMatch(accepted));
  });
});
