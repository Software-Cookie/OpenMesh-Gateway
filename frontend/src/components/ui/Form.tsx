import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import clsx from 'clsx';

export const controlClass = clsx(
  'block w-full min-w-0 rounded-lg border-0 bg-white px-3 py-2 text-sm text-ink shadow-card ring-1 ring-inset ring-ink/15',
  'placeholder:text-ink/35 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-accent',
  'disabled:cursor-not-allowed disabled:bg-ink/[0.03] disabled:text-ink/50',
);

export function Field({
  label,
  hint,
  error,
  children,
  className,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  children: (id: string) => ReactNode;
  className?: string;
}) {
  const id = useId();
  return (
    <div className={clsx('min-w-0', className)}>
      <label htmlFor={id} className="mb-1.5 flex items-baseline gap-1.5 text-[13px] font-medium text-ink/80">
        {label}
        {hint && <span className="font-normal text-ink/45">· {hint}</span>}
      </label>
      {children(id)}
      {error && <p className="mt-1 text-xs font-medium text-ink">{error}</p>}
    </div>
  );
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { mono?: boolean }>(
  function Input({ className, mono, ...rest }, ref) {
    return <input ref={ref} className={clsx(controlClass, mono && 'font-mono text-[13px]', className)} {...rest} />;
  },
);

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, children, ...rest },
  ref,
) {
  return (
    <select ref={ref} className={clsx(controlClass, 'pr-8', className)} {...rest}>
      {children}
    </select>
  );
});

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { mono?: boolean }>(
  function Textarea({ className, mono, ...rest }, ref) {
    return <textarea ref={ref} className={clsx(controlClass, 'resize-y', mono && 'font-mono text-[13px]', className)} {...rest} />;
  },
);

/** Server-side validation errors from the gateway's ApiError body. */
export function FormErrors({ message, details }: { message?: string | null; details?: string[] }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-lg border-l-4 border-ink bg-ink/[0.04] px-3.5 py-2.5 text-sm text-ink">
      <p className="font-semibold">{message}</p>
      {details && details.length > 0 && (
        <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[13px] text-ink/80">
          {details.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  className,
}: {
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: ReactNode; disabled?: boolean }[];
  className?: string;
}) {
  return (
    <div role="tablist" className={clsx('inline-flex rounded-lg bg-ink/[0.05] p-0.5', className)}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={value === option.value}
          disabled={option.disabled}
          onClick={() => onChange(option.value)}
          className={clsx(
            'rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40',
            value === option.value ? 'bg-white text-ink shadow-card' : 'text-ink/60 hover:text-ink',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
