import clsx from 'clsx';

export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={clsx('h-7 w-7', className)} aria-hidden>
      <rect width="32" height="32" rx="7" fill="#0B1F3A" />
      <rect x="0.5" y="0.5" width="31" height="31" rx="6.5" fill="none" stroke="#fff" strokeOpacity="0.2" />
      <path d="M10 11 16 22 22 11M10 11h12" stroke="#fff" strokeWidth="1.6" fill="none" />
      <circle cx="10" cy="11" r="3" fill="#fff" />
      <circle cx="22" cy="11" r="3" fill="#fff" />
      <circle cx="16" cy="22" r="3.5" fill="#2563EB" />
    </svg>
  );
}

export function Logo({ inverted = true }: { inverted?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <LogoMark />
      <div className="leading-tight">
        <p className={clsx('text-[15px] font-semibold', inverted ? 'text-white' : 'text-ink')}>OpenMesh Gateway</p>
        <p className={clsx('text-[10.5px] font-medium uppercase tracking-[0.12em]', inverted ? 'text-white/55' : 'text-ink/50')}>
          Control plane
        </p>
      </div>
    </div>
  );
}
