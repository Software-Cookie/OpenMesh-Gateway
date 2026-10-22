import { useEffect, useState } from 'react';
import clsx from 'clsx';
import { Activity } from 'lucide-react';
import { api, send } from '../lib/api';
import type { BreakerState, CircuitBreaker } from '../lib/types';
import { StateMachine } from '../components/StateMachine';
import { ScenarioCard } from './ScenarioCard';
import { StepFailure, poll, useSteps } from './useSteps';

const BREAKER = 'betaCircuitBreaker';

async function breakerState(): Promise<BreakerState> {
  const breakers = await api.get<CircuitBreaker[]>('/admin/v1/circuit-breakers');
  return breakers.find((b) => b.name === BREAKER)?.state ?? 'CLOSED';
}

interface Call {
  status: number;
  ms: number;
  reason?: string;
}

export function CircuitBreakerDemo() {
  const [state, setState] = useState<BreakerState>('CLOSED');
  const [calls, setCalls] = useState<Call[]>([]);
  const { steps, running, outcome, run } = useSteps([
    'Upstream starts failing: send 6 calls that return HTTP 500',
    'Breaker opens: the next call fails fast without touching the service',
    'After 10 s the breaker lets trial calls through (half-open)',
    'Healthy trial calls close the breaker again',
  ]);

  // Keep the diagram live while the demo runs.
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void breakerState().then(setState).catch(() => {}), 700);
    return () => window.clearInterval(timer);
  }, [running]);

  const call = async (path: string) => {
    const r = await send<{ reason?: string }>('GET', path);
    const entry = { status: r.status, ms: r.durationMs, reason: r.data?.reason };
    setCalls((c) => [...c, entry]);
    return entry;
  };

  const start = () =>
    run(async (step) => {
      setCalls([]);
      setState(await breakerState());
      await step(
        0,
        async () => {
          for (let i = 0; i < 6; i++) await call('/service-beta/chaos/error?status=500');
        },
        () => 'Gateway answered 502 UPSTREAM_ERROR while recording each failure',
      );
      await step(
        1,
        async () => {
          const result = await call('/service-beta/inventory');
          if (result.reason !== 'CIRCUIT_OPEN') throw new StepFailure(`Expected CIRCUIT_OPEN, got HTTP ${result.status}`);
          setState('OPEN');
          return result;
        },
        (r) => `503 CIRCUIT_OPEN in ${r.ms} ms; the failing service gets time to recover`,
      );
      await step(
        2,
        () => poll(async () => ((await breakerState()) === 'HALF_OPEN' ? true : null), 20_000, 500),
        () => 'Breaker moved to HALF_OPEN automatically',
      );
      await step(
        3,
        async () => {
          for (let i = 0; i < 3; i++) await call('/service-beta/inventory');
          return poll(async () => ((await breakerState()) === 'CLOSED' ? true : null), 5_000, 300);
        },
        () => '3 successful trial calls; traffic flows normally again',
      );
      setState('CLOSED');
    });

  return (
    <ScenarioCard
      icon={<Activity className="h-5 w-5" />}
      title="Circuit breaker"
      proves="A failing service is isolated automatically and recovers on its own, without cascading failures."
      steps={steps}
      running={running}
      outcome={outcome}
      onRun={start}
      successText="Full cycle completed: closed, open, half-open, closed. Takes about 15 seconds."
    >
      <div className="space-y-3">
        <StateMachine state={state} />
        {calls.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {calls.map((c, i) => (
              <span
                key={i}
                title={`${c.status}${c.reason ? ` ${c.reason}` : ''} in ${c.ms} ms`}
                className={clsx(
                  'rounded px-1.5 py-0.5 font-mono text-[10.5px]',
                  c.status >= 200 && c.status < 300 && 'bg-accent text-white',
                  c.reason === 'CIRCUIT_OPEN' && 'bg-ink text-white',
                  c.status >= 300 && c.reason !== 'CIRCUIT_OPEN' && 'bg-white text-ink ring-1 ring-ink/30',
                )}
              >
                {c.status}
              </span>
            ))}
          </div>
        )}
      </div>
    </ScenarioCard>
  );
}
