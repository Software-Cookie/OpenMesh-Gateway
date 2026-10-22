import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Check, Play, RotateCcw, X } from 'lucide-react';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Spinner } from '../components/ui/Feedback';
import type { Step } from './useSteps';

function StepIcon({ status }: { status: Step['status'] }) {
  return (
    <span
      className={clsx(
        'grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-semibold ring-1 ring-inset transition-colors',
        status === 'pending' && 'bg-white text-ink/40 ring-ink/15',
        status === 'running' && 'bg-accent/10 text-accent ring-accent/30',
        status === 'done' && 'bg-accent text-white ring-accent',
        status === 'failed' && 'bg-ink text-white ring-ink',
      )}
    >
      {status === 'running' && <Spinner className="h-3.5 w-3.5" />}
      {status === 'done' && <Check className="h-3.5 w-3.5" />}
      {status === 'failed' && <X className="h-3.5 w-3.5" />}
    </span>
  );
}

export function ScenarioCard({
  icon,
  title,
  proves,
  steps,
  running,
  outcome,
  onRun,
  successText,
  children,
  controls,
}: {
  icon: ReactNode;
  title: string;
  proves: string;
  steps: Step[];
  running: boolean;
  outcome: 'success' | 'failure' | null;
  onRun: () => void;
  successText?: ReactNode;
  children?: ReactNode;
  controls?: ReactNode;
}) {
  const started = running || outcome !== null;
  return (
    <Card className="flex flex-col">
      <div className="flex items-start gap-3.5 p-5">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent">{icon}</span>
        <div className="min-w-0 flex-1">
          <h2 className="text-[15px] font-semibold">{title}</h2>
          <p className="mt-0.5 text-[13px] text-ink/60">{proves}</p>
        </div>
      </div>

      <div className="flex-1 space-y-4 px-5 pb-5">
        <ol className="space-y-2.5">
          {steps.map((step, index) => (
            <li key={step.title} className="flex gap-3">
              <StepIcon status={step.status} />
              <div className="min-w-0 pt-0.5">
                <p className={clsx('text-[13px]', step.status === 'pending' ? 'text-ink/50' : 'font-medium text-ink')}>
                  <span className="mr-1 text-ink/35">{index + 1}.</span>
                  {step.title}
                </p>
                {step.detail && <div className="mt-0.5 text-[12.5px] text-ink/60">{step.detail}</div>}
              </div>
            </li>
          ))}
        </ol>
        {started && children}
        {outcome === 'success' && successText && (
          <div className="animate-fade-in rounded-lg bg-accent px-3.5 py-2.5 text-[13px] font-medium text-white">{successText}</div>
        )}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink/[0.07] px-5 py-3.5">
        <div className="min-w-0">{controls}</div>
        <Button
          variant={outcome ? 'secondary' : 'primary'}
          onClick={onRun}
          loading={running}
          icon={outcome ? <RotateCcw className="h-4 w-4" /> : <Play className="h-4 w-4" />}
        >
          {running ? 'Running' : outcome ? 'Run again' : 'Run demo'}
        </Button>
      </div>
    </Card>
  );
}
