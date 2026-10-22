import clsx from 'clsx';
import type { BreakerState } from '../lib/types';

const NODES: { state: BreakerState; label: string; hint: string }[] = [
  { state: 'CLOSED', label: 'Closed', hint: 'Calls flow normally' },
  { state: 'OPEN', label: 'Open', hint: 'Calls fail fast' },
  { state: 'HALF_OPEN', label: 'Half-open', hint: 'Trial calls' },
];

/** Circuit breaker lifecycle with the current state highlighted. */
export function StateMachine({ state }: { state: BreakerState }) {
  const current = state === 'FORCED_OPEN' ? 'OPEN' : state;
  return (
    <div className="flex items-stretch gap-1.5">
      {NODES.map((node, index) => {
        const active = node.state === current;
        return (
          <div key={node.state} className="flex flex-1 items-center gap-1.5">
            <div
              className={clsx(
                'flex-1 rounded-lg px-2.5 py-2 text-center ring-1 ring-inset transition-all duration-300',
                !active && 'bg-white text-ink/45 ring-ink/10',
                active && node.state === 'CLOSED' && 'bg-accent text-white ring-accent',
                active && node.state === 'OPEN' && 'bg-ink text-white ring-ink',
                active && node.state === 'HALF_OPEN' && 'bg-white text-ink ring-2 ring-ink',
              )}
            >
              <p className="text-[13px] font-semibold">{node.label}</p>
              <p className={clsx('text-[11px]', active ? 'opacity-80' : 'opacity-70')}>{node.hint}</p>
            </div>
            {index < NODES.length - 1 && <span className="text-ink/25">→</span>}
          </div>
        );
      })}
    </div>
  );
}
