import type { ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import clsx from 'clsx';

export function Table({ children }: { children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-[13px]">{children}</table>
    </div>
  );
}

export function Th({ className, numeric, ...rest }: ThHTMLAttributes<HTMLTableCellElement> & { numeric?: boolean }) {
  return (
    <th
      className={clsx(
        'whitespace-nowrap border-b border-ink/[0.08] px-5 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wider text-ink/50',
        numeric && 'text-right',
        className,
      )}
      {...rest}
    />
  );
}

export function Td({ className, numeric, ...rest }: TdHTMLAttributes<HTMLTableCellElement> & { numeric?: boolean }) {
  return (
    <td
      className={clsx('border-b border-ink/[0.06] px-5 py-3 align-top text-ink', numeric && 'text-right tabular-nums', className)}
      {...rest}
    />
  );
}

export function Tr({ children, className }: { children: ReactNode; className?: string }) {
  return <tr className={clsx('transition-colors last:[&>td]:border-0 hover:bg-ink/[0.02]', className)}>{children}</tr>;
}
