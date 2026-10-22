import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import clsx from 'clsx';
import { Spinner } from './Feedback';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

const variants: Record<Variant, string> = {
  primary: 'bg-accent text-white hover:bg-accent-strong disabled:hover:bg-accent shadow-card',
  secondary: 'bg-white text-ink ring-1 ring-inset ring-ink/15 hover:bg-ink/[0.04] hover:ring-ink/25 shadow-card',
  ghost: 'text-ink/70 hover:bg-ink/[0.05] hover:text-ink',
  danger: 'bg-white text-ink ring-1 ring-inset ring-ink/30 hover:bg-ink hover:text-white hover:ring-ink',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-[13px] gap-1.5',
  md: 'h-9 px-4 text-sm gap-2',
};

/** Button styling for elements that are not buttons, e.g. router links. */
export function buttonClasses(variant: Variant = 'secondary', size: Size = 'md', className?: string) {
  return clsx(
    'inline-flex shrink-0 items-center justify-center whitespace-nowrap rounded-lg font-medium transition-colors',
    'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
    'disabled:cursor-not-allowed disabled:opacity-50',
    variants[variant],
    sizes[size],
    className,
  );
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading, icon, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      className={buttonClasses(variant, size, className)}
      {...rest}
    >
      {loading ? <Spinner className="h-3.5 w-3.5" /> : icon}
      {children}
    </button>
  );
});
