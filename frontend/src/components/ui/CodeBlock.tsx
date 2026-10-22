import { useState } from 'react';
import clsx from 'clsx';
import { Check, Copy } from 'lucide-react';

export function CopyButton({ value, label = 'Copy', className }: { value: string; label?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        } catch {
          /* clipboard blocked: user can still select the text */
        }
      }}
      className={clsx(
        'inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium transition-colors',
        'text-ink/60 hover:bg-ink/5 hover:text-ink',
        className,
      )}
    >
      {copied ? <Check className="h-3.5 w-3.5 text-accent" /> : <Copy className="h-3.5 w-3.5" />}
      {copied ? 'Copied' : label}
    </button>
  );
}

/** Dark code panel; pretty-prints JSON values. */
export function CodeBlock({ value, className, maxHeight = 420 }: { value: unknown; className?: string; maxHeight?: number }) {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  return (
    <div className={clsx('group relative', className)}>
      <pre
        className="overflow-auto rounded-lg bg-ink p-4 font-mono text-[12px] leading-relaxed text-white/90"
        style={{ maxHeight }}
      >
        {text || '(empty body)'}
      </pre>
      {text && (
        <CopyButton
          value={text}
          className="absolute right-2 top-2 bg-white/10 text-white/80 opacity-0 hover:bg-white/20 hover:text-white group-hover:opacity-100"
        />
      )}
    </div>
  );
}
