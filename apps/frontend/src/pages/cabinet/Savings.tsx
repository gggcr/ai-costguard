// Сколько сэкономил кабинет (ADR 0061): места, выданные из пула вместо покупки, и покупки, которые не понадобились.
import { Card, Empty } from '../../components/ui';
import { useApi } from './useApi';

interface Savings {
  since: string; seatsFromPool: number; reassignedIdle: number; seatsMonthly: number;
  purchasesRefused: number; purchasesMonthly: number; totalMonthly: number; byMonth: { month: string; saved: number }[];
}

const rub = (n: number) => new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 0 }).format(n);
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

export function SavingsCard() {
  const { data, error } = useApi<Savings>('/api/v1/cabinet/savings?months=12');
  if (error) return null;
  if (!data) return <Empty>Загрузка…</Empty>;
  const last = data.byMonth[data.byMonth.length - 1];
  const max = Math.max(1, ...data.byMonth.map((m) => m.saved));
  return (
    <Card title="Экономия от пула лицензий и проверки покупок">
      <div className="stat-row">
        <div><span className="muted small block">В этом месяце</span><strong>{rub(last?.saved ?? 0)}</strong></div>
        <div><span className="muted small block">За год, в месяц</span><strong>{rub(data.totalMonthly)}</strong></div>
        <div><span className="muted small block">Мест не докупили</span><strong>{data.seatsFromPool}</strong><span className="muted small block">из них у простаивающих: {data.reassignedIdle}</span></div>
        <div><span className="muted small block">Покупок не понадобилось</span><strong>{data.purchasesRefused}</strong><span className="muted small block">{rub(data.purchasesMonthly)} в месяц</span></div>
      </div>
      <div className="bars" role="img" aria-label="Экономия по месяцам">
        {data.byMonth.map((m) => (
          <div key={m.month} className="bar-col" title={`${m.month}: ${rub(m.saved)}`}>
            <div className="bar" style={{ height: `${Math.round((m.saved / max) * 100)}%` }} />
            <span className="muted small">{MONTHS[Number(m.month.slice(5)) - 1]}</span>
          </div>
        ))}
      </div>
      <p className="muted small">Место из пула — цена места подписки в месяц; отклонённая покупка — её сумма в месяц (годовая — /12). Свои траты сотрудников — на вкладке «Свои траты сотрудников»: это сервисы, которые стоит купить.</p>
    </Card>
  );
}
