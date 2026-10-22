import { useEffect, useRef, useState, type MouseEvent } from 'react';
import clsx from 'clsx';

/** Tracks an element's width so SVG charts render crisp text at any size. */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    setWidth(element.clientWidth);
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

export interface Series {
  key: string;
  label: string;
  tone: 'accent' | 'ink' | 'ink-dashed';
  values: number[];
  format?: (value: number) => string;
}

const STROKES = { accent: '#2563EB', ink: '#0B1F3A', 'ink-dashed': '#0B1F3A' } as const;

function niceMax(value: number): number {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * magnitude >= value) ?? 10;
  return step * magnitude;
}

/** Multi-series line chart with gridlines, a hover crosshair and tooltip. */
export function LineChart({ series, height = 220, labels, emptyText = 'Collecting data…' }: {
  series: Series[];
  height?: number;
  labels?: [string, string];
  emptyText?: string;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const length = Math.max(0, ...series.map((s) => s.values.length));
  const pad = { top: 12, right: 12, bottom: 22, left: 40 };
  const innerW = Math.max(width - pad.left - pad.right, 10);
  const innerH = height - pad.top - pad.bottom;
  const max = niceMax(Math.max(0, ...series.flatMap((s) => s.values)));

  const x = (i: number) => pad.left + (length <= 1 ? innerW : (i / (length - 1)) * innerW);
  const y = (v: number) => pad.top + innerH - (v / max) * innerH;

  const paths = series.map((s) => ({
    ...s,
    line: s.values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' '),
  }));
  const area = paths[0] && length > 1 ? `${paths[0].line} L${x(length - 1)},${y(0)} L${x(0)},${y(0)} Z` : '';

  const onMove = (event: MouseEvent<SVGSVGElement>) => {
    if (length < 2) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = (event.clientX - rect.left - pad.left) / innerW;
    setHover(Math.min(length - 1, Math.max(0, Math.round(ratio * (length - 1)))));
  };

  return (
    <div ref={ref} className="relative w-full" style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} onMouseMove={onMove} onMouseLeave={() => setHover(null)} role="img" aria-label="Traffic chart">
          <defs>
            <linearGradient id="area-fill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#2563EB" stopOpacity="0.16" />
              <stop offset="100%" stopColor="#2563EB" stopOpacity="0" />
            </linearGradient>
          </defs>
          {[0, 0.5, 1].map((t) => (
            <g key={t}>
              <line x1={pad.left} x2={width - pad.right} y1={y(max * t)} y2={y(max * t)} stroke="#0B1F3A" strokeOpacity={t === 0 ? 0.15 : 0.07} />
              <text x={pad.left - 8} y={y(max * t) + 4} textAnchor="end" fontSize="11" fill="#0B1F3A" fillOpacity="0.5">
                {Number.isInteger(max * t) ? max * t : (max * t).toFixed(1)}
              </text>
            </g>
          ))}
          {labels && (
            <>
              <text x={pad.left} y={height - 4} fontSize="11" fill="#0B1F3A" fillOpacity="0.5">{labels[0]}</text>
              <text x={width - pad.right} y={height - 4} textAnchor="end" fontSize="11" fill="#0B1F3A" fillOpacity="0.5">{labels[1]}</text>
            </>
          )}
          {area && <path d={area} fill="url(#area-fill)" />}
          {paths.map((p) => (
            <path
              key={p.key}
              d={p.line}
              fill="none"
              stroke={STROKES[p.tone]}
              strokeWidth={p.tone === 'accent' ? 2 : 1.6}
              strokeDasharray={p.tone === 'ink-dashed' ? '4 4' : undefined}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          ))}
          {hover !== null && (
            <g>
              <line x1={x(hover)} x2={x(hover)} y1={pad.top} y2={pad.top + innerH} stroke="#0B1F3A" strokeOpacity="0.2" />
              {series.map((s) => (
                <circle key={s.key} cx={x(hover)} cy={y(s.values[hover] ?? 0)} r="3.5" fill="#fff" stroke={STROKES[s.tone]} strokeWidth="2" />
              ))}
            </g>
          )}
        </svg>
      )}
      {length < 2 && (
        <div className="absolute inset-0 grid place-items-center text-[13px] text-ink/50">{emptyText}</div>
      )}
      {hover !== null && (
        <div
          className="pointer-events-none absolute top-2 z-10 min-w-[150px] rounded-lg bg-ink px-3 py-2 text-xs text-white shadow-pop"
          style={{ left: Math.min(x(hover) + 12, Math.max(width - 170, 0)) }}
        >
          {series.map((s) => (
            <div key={s.key} className="flex items-center justify-between gap-4 py-0.5">
              <span className="flex items-center gap-1.5 text-white/75">
                <LegendSwatch tone={s.tone} inverted />
                {s.label}
              </span>
              <span className="font-mono">{(s.format ?? ((v: number) => v.toFixed(1)))(s.values[hover] ?? 0)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function LegendSwatch({ tone, inverted }: { tone: Series['tone']; inverted?: boolean }) {
  if (tone === 'ink-dashed') {
    return <span className={clsx('inline-block w-3 border-t-2 border-dashed', inverted ? 'border-white' : 'border-ink')} />;
  }
  return <span className={clsx('inline-block h-2 w-3 rounded-sm', tone === 'accent' ? 'bg-accent' : inverted ? 'bg-white' : 'bg-ink')} />;
}

export function Legend({ series }: { series: Pick<Series, 'key' | 'label' | 'tone'>[] }) {
  return (
    <div className="flex flex-wrap items-center gap-4 text-xs text-ink/60">
      {series.map((s) => (
        <span key={s.key} className="flex items-center gap-1.5">
          <LegendSwatch tone={s.tone} />
          {s.label}
        </span>
      ))}
    </div>
  );
}

/** Tiny trend line for KPI tiles. */
export function Sparkline({ values, className }: { values: number[]; className?: string }) {
  if (values.length < 2) return <div className={clsx('h-8', className)} />;
  const max = Math.max(...values, 1);
  const points = values.map((v, i) => `${(i / (values.length - 1)) * 100},${30 - (v / max) * 28}`).join(' ');
  return (
    <svg viewBox="0 0 100 32" preserveAspectRatio="none" className={clsx('h-8 w-full', className)} aria-hidden>
      <polyline points={points} fill="none" stroke="#2563EB" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** Horizontal bars: primary value in accent, secondary (e.g. rejected) as an ink segment. */
export function BarList({ items, emptyText }: {
  items: { label: string; value: number; secondary?: number; hint?: string }[];
  emptyText: string;
}) {
  const max = Math.max(1, ...items.map((i) => i.value));
  if (!items.length) return <p className="py-8 text-center text-[13px] text-ink/50">{emptyText}</p>;
  return (
    <ul className="space-y-3">
      {items.map((item) => (
        <li key={item.label}>
          <div className="mb-1 flex items-baseline justify-between gap-3 text-[13px]">
            <span className="truncate font-mono text-ink">{item.label}</span>
            <span className="shrink-0 tabular-nums text-ink/60">{item.hint}</span>
          </div>
          <div className="flex h-2 overflow-hidden rounded-full bg-ink/[0.06]">
            <div className="h-full bg-accent" style={{ width: `${((item.value - (item.secondary ?? 0)) / max) * 100}%` }} />
            {item.secondary ? <div className="h-full bg-ink" style={{ width: `${(item.secondary / max) * 100}%` }} /> : null}
          </div>
        </li>
      ))}
    </ul>
  );
}
