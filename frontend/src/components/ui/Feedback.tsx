import type { ReactNode } from 'react';
import clsx from 'clsx';
import { AlertTriangle } from 'lucide-react';
import { ApiError } from '../../lib/api';

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={clsx('animate-spin text-current', className ?? 'h-4 w-4')} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.2" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx('animate-pulse rounded-md bg-ink/[0.06]', className)} />;
}

export function TableSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-5">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  );
}

export function EmptyState({ icon, title, description, action }: {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      {icon && <div className="mb-3 grid h-11 w-11 place-items-center rounded-full bg-accent/10 text-accent">{icon}</div>}
      <p className="text-sm font-semibold text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-[13px] text-ink/60">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong';
  const trace = error instanceof ApiError ? error.traceId : undefined;
  return (
    <div className="flex flex-col items-center px-6 py-12 text-center">
      <div className="mb-3 grid h-11 w-11 place-items-center rounded-full bg-ink text-white">
        <AlertTriangle className="h-5 w-5" />
      </div>
      <p className="text-sm font-semibold text-ink">{message}</p>
      {trace && <p className="mt-1 font-mono text-xs text-ink/50">trace {trace}</p>}
      {onRetry && (
        <button onClick={onRetry} className="mt-4 text-sm font-medium text-accent hover:underline">
          Try again
        </button>
      )}
    </div>
  );
}
