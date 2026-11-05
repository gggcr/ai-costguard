// Простые графики без библиотек: горизонтальные столбцы и линия с прогнозом.
// Одна серия = один цвет (--series-1), подписи значений текстовыми токенами, подсказка при наведении.
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { fmtRub } from '../lib';

export function BarList({ items, unit = fmtRub }: { items: { label: string; value: number; to?: string; sub?: string }[]; unit?: (v: number) => string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <ul className="barlist">
      {items.map((i) => {
        const row = (
          <>
            <span className="barlist-label">{i.label}{i.sub && <span className="muted small"> · {i.sub}</span>}</span>
            <span className="barlist-track" title={`${i.label}: ${unit(i.value)}`}>
              <span className="barlist-bar" style={{ width: `${(i.value / max) * 100}%` }} />
            </span>
            <span className="barlist-value">{unit(i.value)}</span>
          </>
        );
        return <li key={i.label}>{i.to ? <Link to={i.to} className="barlist-row">{row}</Link> : <div className="barlist-row">{row}</div>}</li>;
      })}
    </ul>
  );
}

export interface LinePoint { label: string; value: number; forecast?: boolean; low?: number; high?: number }

export function LineChart({ points, height = 220, title }: { points: LinePoint[]; height?: number; title: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 720;
  const H = height;
  const pad = { l: 64, r: 16, t: 16, b: 28 };
  const max = Math.max(...points.map((p) => p.high ?? p.value)) * 1.08;
  const x = (i: number) => pad.l + (i * (W - pad.l - pad.r)) / Math.max(1, points.length - 1);
  const y = (v: number) => pad.t + (1 - v / max) * (H - pad.t - pad.b);
  const firstForecast = points.findIndex((p) => p.forecast);
  const actual = points.filter((p) => !p.forecast);
  const path = (arr: { i: number; v: number }[]) => arr.map((p, k) => `${k ? 'L' : 'M'}${x(p.i).toFixed(1)},${y(p.v).toFixed(1)}`).join(' ');
  const actualPath = path(actual.map((p, i) => ({ i, v: p.value })));
  const fc = firstForecast > 0 ? points.slice(firstForecast - 1).map((p, k) => ({ i: firstForecast - 1 + k, v: p.value })) : [];
  const band = firstForecast > 0
    ? points.slice(firstForecast).map((p, k) => ({ i: firstForecast + k, lo: p.low ?? p.value, hi: p.high ?? p.value }))
    : [];
  const bandPath = band.length
    ? `M${x(firstForecast - 1)},${y(points[firstForecast - 1].value)} ` + band.map((b) => `L${x(b.i)},${y(b.hi)}`).join(' ') + ' ' + [...band].reverse().map((b) => `L${x(b.i)},${y(b.lo)}`).join(' ') + ' Z'
    : '';
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => max * t);
  const compact = (v: number) => (v >= 1e6 ? `${(v / 1e6).toFixed(1)} млн` : `${Math.round(v / 1000)} тыс`);

  return (
    <div className="linechart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={pad.l} x2={W - pad.r} y1={y(t)} y2={y(t)} className="grid" />
            <text x={pad.l - 8} y={y(t) + 4} className="axis" textAnchor="end">{compact(t)} ₽</text>
          </g>
        ))}
        {points.map((p, i) => (i % Math.ceil(points.length / 8) === 0 || i === points.length - 1) && (
          <text key={p.label} x={x(i)} y={H - 8} className="axis" textAnchor="middle">{p.label}</text>
        ))}
        {bandPath && <path d={bandPath} className="band" />}
        <path d={actualPath} className="line" />
        {fc.length > 1 && <path d={path(fc)} className="line forecast" />}
        {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={pad.t} y2={H - pad.b} className="crosshair" />}
        {points.map((p, i) => (
          <g key={`pt-${p.label}`}>
            {hover === i && <circle cx={x(i)} cy={y(p.value)} r={5} className={`marker ${p.forecast ? 'forecast' : ''}`} />}
            <rect x={x(i) - (W - pad.l) / points.length / 2} y={pad.t} width={(W - pad.l) / points.length} height={H - pad.t - pad.b} fill="transparent" onMouseEnter={() => setHover(i)} />
          </g>
        ))}
      </svg>
      {hover !== null && (
        <div className="chart-tip" style={{ left: `${(x(hover) / W) * 100}%` }}>
          <strong>{points[hover].label}</strong>
          <span>{points[hover].forecast ? 'Прогноз' : 'Факт'}: {fmtRub(points[hover].value)}</span>
          {points[hover].forecast && points[hover].low !== undefined && <span className="muted small">от {fmtRub(points[hover].low!)} до {fmtRub(points[hover].high!)}</span>}
        </div>
      )}
      <div className="legend">
        <span><i className="lg-line" /> Факт</span>
        <span><i className="lg-line dashed" /> Прогноз</span>
        <span><i className="lg-band" /> Диапазон прогноза</span>
      </div>
    </div>
  );
}
