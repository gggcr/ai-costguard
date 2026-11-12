// «Запросы сотрудников» (ADR 0053–0057): работодатель видит, что сотрудники просят и сообщают в своих кабинетах.
import { useSearchParams } from 'react-router-dom';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { Empty, PageHeader, Tabs } from '../components/ui';
import { EmployerLicenses } from './cabinet/Licenses';
import { EmployerPayroll } from './cabinet/Payroll';
import { EmployerPurchases } from './cabinet/Purchases';
import { ShadowSummaryCard } from './cabinet/Shadow';
import { SavingsCard } from './cabinet/Savings';
import { EmployerVacations } from './cabinet/Vacations';
import { EmployerLimitRequests } from './cabinet/Agents';
import { AwaitingSignatures } from './cabinet/Signing';

type Tab = 'vacations' | 'payroll' | 'licenses' | 'purchases' | 'spend' | 'limits' | 'sign';

export function People() {
  const { mode } = useStore();
  const { user, canEdit } = useAuth();
  const [params, setParams] = useSearchParams();
  // зарплату видят только owner и finance (ADR 0054)
  const pay = user?.role === 'owner' || user?.role === 'finance';
  const items: { id: Tab; label: string }[] = [{ id: 'vacations', label: 'Отпуска' }];
  if (pay) items.push({ id: 'payroll', label: 'Зарплата' });
  items.push({ id: 'licenses', label: 'Лицензии' }, { id: 'purchases', label: 'Покупки ПО' }, { id: 'spend', label: 'Свои траты сотрудников' });
  if (pay) items.push({ id: 'limits', label: 'Лимиты агентов' }, { id: 'sign', label: 'На подпись' });
  const asked = params.get('tab') as Tab | null;
  const tab: Tab = asked && items.some((i) => i.id === asked) ? asked : 'vacations';
  if (mode !== 'api') return <Empty>Раздел работает при подключении к серверу CostGuard</Empty>;
  return (
    <>
      <PageHeader title="Запросы сотрудников" subtitle="Что сотрудники просят и сообщают в своих кабинетах" />
      <SavingsCard />
      <Tabs value={tab} onChange={(t) => setParams({ tab: t })} items={items} />
      {tab === 'vacations' && <EmployerVacations canManageHeads={canEdit('org')} />}
      {tab === 'payroll' && pay && <EmployerPayroll />}
      {tab === 'licenses' && <EmployerLicenses canDecide={user?.role === 'owner' || user?.role === 'it_admin'} />}
      {tab === 'purchases' && <EmployerPurchases canDecide={pay} />}
      {tab === 'spend' && <ShadowSummaryCard />}
      {tab === 'limits' && pay && <EmployerLimitRequests />}
      {tab === 'sign' && pay && <AwaitingSignatures />}
    </>
  );
}
