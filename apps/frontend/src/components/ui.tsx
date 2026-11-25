// Базовые компоненты интерфейса
import { useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { FindingStatus, GatewayCode, ImportStatus, Approval } from '../api/types';
import { useStore } from '../api/store';

// ——— Иконки (простые линейные SVG) ———
const paths: Record<string, string> = {
  dashboard: 'M3 13h8V3H3zm10 8h8V11h-8zM3 21h8v-6H3zm10-18v6h8V3z',
  agents: 'M12 2a4 4 0 0 1 4 4v2a4 4 0 0 1-8 0V6a4 4 0 0 1 4-4zm-7 20v-2a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v2',
  policies: 'M12 2l8 3v6c0 5-3.4 9.3-8 11-4.6-1.7-8-6-8-11V5z',
  budgets: 'M3 7h18v12H3zM3 7l3-4h12l3 4M16 13h2',
  approvals: 'M9 11l3 3 8-8M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9',
  operations: 'M4 6h16M4 12h16M4 18h10',
  software: 'M4 4h16v12H4zM8 20h8M12 16v4',
  assets: 'M5 5h14v10H5zM2 19h20M9 19l1-4h4l1 4',
  imports: 'M12 3v12m0 0l-4-4m4 4l4-4M4 17v3h16v-3',
  findings: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm10 17l-5-5',
  analytics: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  org: 'M12 3v4M5 11h14M5 11v4M19 11v4M12 7v8M3 15h4v4H3zM10 15h4v4h-4zM17 15h4v4h-4z',
  notifications: 'M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  plus: 'M12 5v14M5 12h14',
  close: 'M6 6l12 12M18 6L6 18',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm10 17l-5-5',
  check: 'M5 12l5 5L20 7',
  pause: 'M8 5v14M16 5v14',
  play: 'M7 4l13 8-13 8z',
  download: 'M12 3v12m0 0l-4-4m4 4l4-4M4 21h16',
  key: 'M15 7a4 4 0 1 1-3.9 5H3v4h3v-2h2v2h3',
  menu: 'M3 6h18M3 12h18M3 18h18',
  refresh: 'M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7',
  alert: 'M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
};

export function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[name] ?? paths.dashboard} />
    </svg>
  );
}

// ——— Кнопки ———
type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
export function Button({ variant = 'secondary', icon, children, className = '', ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; icon?: string }) {
  return (
    <button className={`btn btn-${variant} ${className}`} {...rest}>
      {icon && <Icon name={icon} size={16} />}
      {children}
    </button>
  );
}

// ——— Бейджи и статусы (цвет всегда вместе со значком и подписью) ———
export type Tone = 'neutral' | 'good' | 'warning' | 'serious' | 'critical' | 'info';
const toneIcon: Record<Tone, string> = { neutral: '•', good: '✓', warning: '!', serious: '!', critical: '×', info: 'i' };

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span className={`badge badge-${tone}`}>
      <span className="badge-icon" aria-hidden="true">{toneIcon[tone]}</span>
      {children}
    </span>
  );
}

export const CODE_META: Record<GatewayCode, { label: string; tone: Tone; http: string }> = {
  allowed: { label: 'Разрешено', tone: 'good', http: '200' },
  '401_unauthorized': { label: 'Неверный ключ', tone: 'critical', http: '401' },
  '402_budget_exceeded': { label: 'Бюджет исчерпан', tone: 'critical', http: '402' },
  '403_policy_denied': { label: 'Запрещено мандатом', tone: 'serious', http: '403' },
  '409_duplicate_operation': { label: 'Повтор операции', tone: 'warning', http: '409' },
  '423_agent_suspended': { label: 'Агент остановлен', tone: 'critical', http: '423' },
  '429_rate_limited': { label: 'Замедлен', tone: 'warning', http: '429' },
  approval_rejected: { label: 'Отклонено человеком', tone: 'serious', http: '403' },
  // the gateway answers the agent 403 approval_required and keeps the operation until a person decides
  pending_approval: { label: 'Ждёт согласования', tone: 'warning', http: '403' },
};
export function CodeBadge({ code }: { code: GatewayCode }) {
  const m = CODE_META[code];
  return <Badge tone={m.tone}>{m.http !== '200' ? `${m.http} · ` : ''}{m.label}</Badge>;
}

export const FINDING_META: Record<FindingStatus, { label: string; tone: Tone }> = {
  new: { label: 'Новая', tone: 'info' },
  review: { label: 'На проверке', tone: 'warning' },
  accepted: { label: 'Принята', tone: 'info' },
  in_progress: { label: 'Выполняется', tone: 'info' },
  confirmed: { label: 'Экономия подтверждена', tone: 'good' },
  rejected: { label: 'Отклонена', tone: 'neutral' },
  postponed: { label: 'Отложена', tone: 'neutral' },
  auto_closed: { label: 'Закрыта автоматически', tone: 'neutral' },
};
export function FindingBadge({ status }: { status: FindingStatus }) {
  const m = FINDING_META[status];
  return <Badge tone={m.tone}>{m.label}</Badge>;
}

export const IMPORT_META: Record<ImportStatus, { label: string; tone: Tone }> = {
  uploaded: { label: 'Разбирается', tone: 'info' },
  mapping: { label: 'Нужно сопоставить колонки', tone: 'warning' },
  review: { label: 'Ручная проверка', tone: 'warning' },
  done: { label: 'Готово', tone: 'good' },
  failed: { label: 'Ошибка', tone: 'critical' },
};

export const APPROVAL_META: Record<Approval['status'], { label: string; tone: Tone }> = {
  pending: { label: 'Ждёт решения', tone: 'warning' },
  approved: { label: 'Одобрено', tone: 'good' },
  rejected: { label: 'Отклонено', tone: 'serious' },
  expired: { label: 'Истёк срок', tone: 'neutral' },
};

export function RiskBadge({ score }: { score: number }) {
  const tone: Tone = score >= 80 ? 'critical' : score >= 60 ? 'serious' : score >= 40 ? 'warning' : 'good';
  return <Badge tone={tone}>Риск {score}</Badge>;
}

// ——— Раскладка ———
export function PageHeader({ title, subtitle, actions, back }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; back?: { to: string; label: string } }) {
  return (
    <div className="page-header">
      <div>
        {back && <Link className="back-link" to={back.to}>← {back.label}</Link>}
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Card({ title, actions, children, className = '' }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-header">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value, hint, to }: { label: string; value: ReactNode; hint?: ReactNode; to?: string }) {
  const body = (
    <>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </>
  );
  return to ? <Link to={to} className="stat stat-link">{body}</Link> : <div className="stat">{body}</div>;
}

export function Progress({ value, max, label }: { value: number; max: number; label?: string }) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  const tone = pct >= 95 ? 'critical' : pct >= 80 ? 'warning' : 'good';
  return (
    <div className="progress" role="meter" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100} aria-label={label ?? 'Заполнение'}>
      <div className={`progress-bar progress-${tone}`} style={{ width: `${pct}%` }} />
    </div>
  );
}

export function Empty({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <p>{children}</p>
      {action}
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { id: T; label: ReactNode }[] }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((it) => (
        <button key={it.id} role="tab" aria-selected={value === it.id} className={`tab ${value === it.id ? 'active' : ''}`} onClick={() => onChange(it.id)}>
          {it.label}
        </button>
      ))}
    </div>
  );
}

// ——— Модальное окно ———
export function Modal({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    ref.current?.querySelector<HTMLElement>('.modal-body input, .modal-body select, .modal-body textarea, .modal-footer button')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <header className="modal-header">
          <h2>{title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Закрыть"><Icon name="close" /></button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>
  );
}

// Кнопка с подтверждением действия
export function ConfirmButton({ title, text, confirmLabel = 'Подтвердить', onConfirm, variant = 'secondary', icon, children, disabled }: {
  title: string; text: ReactNode; confirmLabel?: string; onConfirm: () => void; variant?: Variant; icon?: string; children: ReactNode; disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant={variant} icon={icon} onClick={() => setOpen(true)} disabled={disabled}>{children}</Button>
      <Modal open={open} onClose={() => setOpen(false)} title={title} footer={
        <>
          <Button variant="ghost" onClick={() => setOpen(false)}>Отмена</Button>
          <Button variant={variant === 'danger' ? 'danger' : 'primary'} onClick={() => { onConfirm(); setOpen(false); }}>{confirmLabel}</Button>
        </>
      }>
        <p>{text}</p>
      </Modal>
    </>
  );
}

// ——— Поля форм ———
export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

// ——— Тосты ———
export function Toasts() {
  const { toasts, dismissToast } = useStore();
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.level}`}>
          <span>{t.text}</span>
          <button className="icon-btn" onClick={() => dismissToast(t.id)} aria-label="Скрыть"><Icon name="close" size={14} /></button>
        </div>
      ))}
    </div>
  );
}

export function Forbidden() {
  return (
    <div className="card forbidden">
      <h1>Нет доступа</h1>
      <p className="muted">У вашей роли нет доступа к этому разделу. Смените пользователя в правом верхнем углу.</p>
      <Link to="/" className="btn btn-primary">На главную</Link>
    </div>
  );
}

export function ReadOnlyNote() {
  return <div className="note">Режим просмотра: у вашей роли нет прав на изменения в этом разделе.</div>;
}
