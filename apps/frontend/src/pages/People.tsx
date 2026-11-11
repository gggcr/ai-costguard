// «Запросы сотрудников» (ADR 0053–0057): работодатель видит, что сотрудники просят и сообщают в своих кабинетах.
import { useSearchParams } from 'react-router-dom';
import { useStore } from '../api/store';
import { useAuth } from '../auth/auth';
import { Empty, PageHeader, Tabs } from '../components/ui';
import { EmployerPayroll } from './cabinet/Payroll';
import { EmployerVacations } from './cabinet/Vacations';

type Tab = 'vacations' | 'payroll';

export function People() {
  const { mode } = useStore();
  const { user, canEdit } = useAuth();
  const [params, setParams] = useSearchParams();
  // зарплату видят только owner и finance (ADR 0054)
  const pay = user?.role === 'owner' || user?.role === 'finance';
  const items: { id: Tab; label: string }[] = [{ id: 'vacations', label: 'Отпуска' }];
  if (pay) items.push({ id: 'payroll', label: 'Зарплата' });
  const asked = params.get('tab') as Tab | null;
  const tab: Tab = asked && items.some((i) => i.id === asked) ? asked : 'vacations';
  if (mode !== 'api') return <Empty>Раздел работает при подключении к серверу CostGuard</Empty>;
  return (
    <>
      <PageHeader title="Запросы сотрудников" subtitle="Что сотрудники просят и сообщают в своих кабинетах" />
      <Tabs value={tab} onChange={(t) => setParams({ tab: t })} items={items} />
      {tab === 'vacations' && <EmployerVacations canManageHeads={canEdit('org')} />}
      {tab === 'payroll' && pay && <EmployerPayroll />}
    </>
  );
}
