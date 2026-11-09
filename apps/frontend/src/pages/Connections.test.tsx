// @vitest-environment jsdom
// Страница «Подключённые системы»: статусы и права ролей (owner и it_admin меняют, остальные смотрят).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { StoreProvider } from '../api/store';
import { AuthProvider } from '../auth/auth';
import { Guard } from '../components/Layout';
import { createSeed } from '../api/mock-data';
import { ConnectionDetail, Connections, CONNECTION_STATUS } from './Connections';

const USER = { owner: 'u-owner', finance: 'u-fin', it_admin: 'u-it', agent_owner: 'u-ao', auditor: 'u-aud' } as const;

function open(role: keyof typeof USER, path = '/connections') {
  localStorage.setItem('costguard-demo-user', USER[role]);
  render(
    <MemoryRouter initialEntries={[path]}>
      <StoreProvider>
        <AuthProvider>
          <Routes>
            <Route path="/connections" element={<Guard section="connections"><Connections /></Guard>} />
            <Route path="/connections/:id" element={<Guard section="connections"><ConnectionDetail /></Guard>} />
          </Routes>
        </AuthProvider>
      </StoreProvider>
    </MemoryRouter>,
  );
}

const row = (type: string) => screen.getByTestId(`connection-${type}`);

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe('статусы', () => {
  it('у каждой системы свой бейдж статуса', () => {
    open('owner');
    expect(within(row('onec')).getByText(CONNECTION_STATUS.connected.label)).toBeTruthy();
    expect(within(row('okta')).getByText(CONNECTION_STATUS.failing.label)).toBeTruthy();
    expect(within(row('tbank')).getByText(CONNECTION_STATUS.pending.label)).toBeTruthy();
    expect(within(row('jamf')).getByText(CONNECTION_STATUS.disconnected.label)).toBeTruthy();
    expect(within(row('bitrix24')).getByText(CONNECTION_STATUS.sync_requested.label)).toBeTruthy();
  });

  it('показывает первую строку ошибки, записи и пометку файла агента', () => {
    open('owner');
    expect(within(row('okta')).getByText('401 Unauthorized')).toBeTruthy();
    expect(within(row('onec')).getByText(/120 за последнюю/)).toBeTruthy();
    expect(within(row('tbank')).getByText('ещё не было')).toBeTruthy();
    expect(within(row('moysklad')).getByText('Настроено в файле агента')).toBeTruthy();
  });

  it('синхронизация переводит систему в «Синхронизация запрошена»', () => {
    open('owner');
    fireEvent.click(within(row('onec')).getByRole('button', { name: 'Синхронизировать' }));
    expect(within(row('onec')).getByText(CONNECTION_STATUS.sync_requested.label)).toBeTruthy();
  });

  it('у системы без секрета блок «Что нужно агенту» выделен', () => {
    open('auditor', '/connections/c-tbank');
    expect(screen.getByRole('alert').textContent).toContain('TBANK_TOKEN');
  });

  it('пустое состояние подсказывает начать с почты или Excel', () => {
    localStorage.setItem('costguard-demo-state-v1', JSON.stringify({ ...createSeed(), connections: [], connectionRuns: [] }));
    open('owner');
    expect(screen.getByText('Пока ничего не подключено')).toBeTruthy();
    expect(screen.getByText(/перешлите счета на почтовый ящик/).textContent).toContain('Excel');
    expect(screen.getByRole('button', { name: 'Подключить систему' })).toBeTruthy();
  });
});

describe('права ролей', () => {
  it.each(['owner', 'it_admin'] as const)('%s подключает, синхронизирует и отключает', (role) => {
    open(role);
    expect(screen.getByRole('button', { name: 'Подключить систему' })).toBeTruthy();
    expect(within(row('onec')).getByRole('button', { name: 'Синхронизировать' })).toBeTruthy();
    expect(within(row('onec')).getByRole('button', { name: 'Отключить' })).toBeTruthy();
    expect(within(row('jamf')).getByRole('button', { name: 'Подключить снова' })).toBeTruthy();
    expect(screen.queryByText(/Режим просмотра/)).toBeNull();
  });

  it.each(['finance', 'auditor'] as const)('%s видит только список', (role) => {
    open(role);
    expect(row('onec')).toBeTruthy();
    expect(screen.getByText(/Режим просмотра/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Подключить систему' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Синхронизировать' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отключить' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Подключить снова' })).toBeNull();
  });

  it('finance на карточке системы тоже без кнопок', () => {
    open('finance', '/connections/c-onec');
    expect(screen.getByText('1С: Бухгалтерия')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Синхронизировать' })).toBeNull();
  });

  it('владельцу агента раздел недоступен', () => {
    open('agent_owner');
    expect(screen.getByText('Нет доступа')).toBeTruthy();
  });
});
