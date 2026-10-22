import type { ReactNode } from 'react';
import clsx from 'clsx';
import type { BreakerState } from '../../lib/types';

/**
 * accent = healthy / positive, solid = strong positive, ink = failure, outline = warning, muted = neutral.
 * Only the two brand colours are used; meaning comes from fill vs outline plus the label.
 */
export type Tone = 'accent' | 'solid' | 'ink' | 'outline' | 'muted';

const tones: Record<Tone, string> = {
  accent: 'bg-accent/10 text-accent-strong ring-accent/20',
  solid: 'bg-accent text-white ring-accent',
  ink: 'bg-ink text-white ring-ink',
  outline: 'bg-white text-ink ring-ink/60 font-semibold',
  muted: 'bg-ink/[0.04] text-ink/70 ring-ink/10',
};

export function Badge({ tone = 'muted', mono, className, children, title }: {
  tone?: Tone;
  mono?: boolean;
  className?: string;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={clsx(
        'inline-flex max-w-full items-center gap-1 truncate rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        mono && 'font-mono text-[11.5px]',
        tones[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}

export function statusTone(status: number): Tone {
  if (status >= 200 && status < 300) return 'accent';
  if (status === 429) return 'outline';
  return 'ink';
}

export function StatusBadge({ status, text }: { status: number; text?: string }) {
  return (
    <Badge tone={statusTone(status)} mono>
      {status === 0 ? 'Network error' : `${status}${text ? ` ${text}` : ''}`}
    </Badge>
  );
}

const breakerLabels: Record<string, [string, Tone]> = {
  CLOSED: ['Closed', 'accent'],
  OPEN: ['Open', 'ink'],
  FORCED_OPEN: ['Forced open', 'ink'],
  HALF_OPEN: ['Half-open', 'outline'],
};

export function BreakerBadge({ state }: { state: BreakerState }) {
  const [label, tone] = breakerLabels[state] ?? [state, 'muted'];
  return <Badge tone={tone}>{label}</Badge>;
}
