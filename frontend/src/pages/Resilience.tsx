import { useState } from 'react';
import { Zap } from 'lucide-react';
import { send } from '../lib/api';
import { formatNumber, formatPercent } from '../lib/format';
import { useCircuitBreakers } from '../lib/queries';
import { Card, CardHeader, PageHeader } from '../components/ui/Card';
import { BreakerBadge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { ErrorState, Skeleton } from '../components/ui/Feedback';
import { StateMachine } from '../components/StateMachine';
import { useToast } from '../components/ui/Toast';

const POLICY = [
  ['Sliding window', 'Last 10 calls, minimum 5 before judging'],
  ['Opens when', '50% of calls fail or take longer than 1.5 s'],
  ['Time limit', '1.5 s per call, then 504 Gateway Timeout'],
  ['Stays open for', '10 s, then allows 3 trial calls'],
  ['Counts as failure', 'Timeouts, connection errors, HTTP 500/502/503/504'],
];

export function ResiliencePage() {
  const breakers = useCircuitBreakers(2000);
  const toast = useToast();
  const [tripping, setTripping] = useState(false);

  async function tripBeta() {
    setTripping(true);
    try {
      for (let i = 0; i < 8; i++) await send('GET', '/service-beta/chaos/error?status=500');
      const next = await send<{ reason?: string }>('GET', '/service-beta/inventory');
      if (next.data?.reason === 'CIRCUIT_OPEN') toast.success('Breaker opened: beta now fails fast with 503 for 10 seconds.');
      else toast.error(`Sent 8 failing calls; the next call returned HTTP ${next.status}.`);
      void breakers.refetch();
    } finally {
      setTripping(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Resilience"
        description="Resilience4j circuit breakers isolate failing services so one bad dependency cannot take the platform down. Updates every 2 seconds."
        actions={
          <Button icon={<Zap className="h-4 w-4" />} loading={tripping} onClick={tripBeta}>
            Simulate beta outage
          </Button>
        }
      />

      {breakers.isError && (
        <Card>
          <ErrorState error={breakers.error} onRetry={() => breakers.refetch()} />
        </Card>
      )}

      <div className="grid gap-5 xl:grid-cols-2">
        {breakers.isLoading && [0, 1].map((i) => <Skeleton key={i} className="h-64 rounded-xl" />)}
        {breakers.data?.map((b) => (
          <Card key={b.name}>
            <CardHeader title={<span className="font-mono">{b.name}</span>} actions={<BreakerBadge state={b.state} />} />
            <div className="space-y-5 p-5">
              <StateMachine state={b.state} />
              <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                {[
                  ['Failure rate', b.failureRate < 0 ? '–' : formatPercent(b.failureRate)],
                  ['Slow calls', b.slowCallRate < 0 ? '–' : formatPercent(b.slowCallRate)],
                  ['Calls in window', formatNumber(b.bufferedCalls)],
                  ['Rejected while open', formatNumber(b.notPermittedCalls)],
                ].map(([label, value]) => (
                  <div key={label}>
                    <dt className="text-xs text-ink/50">{label}</dt>
                    <dd className="mt-0.5 text-lg font-semibold tabular-nums">{value}</dd>
                  </div>
                ))}
              </dl>
              <div>
                <div className="mb-1 flex justify-between text-xs text-ink/50">
                  <span>Failure rate</span>
                  <span>opens at 50%</span>
                </div>
                <div className="relative h-2 overflow-hidden rounded-full bg-ink/[0.06]">
                  <div
                    className={b.failureRate >= 50 ? 'h-full bg-ink' : 'h-full bg-accent'}
                    style={{ width: `${Math.max(b.failureRate, 0)}%`, transition: 'width .4s ease' }}
                  />
                  <div className="absolute inset-y-0 left-1/2 w-px bg-ink/40" />
                </div>
              </div>
            </div>
          </Card>
        ))}
      </div>

      <Card className="mt-5">
        <CardHeader title="Policy" description="Configured in application.yml under resilience4j." />
        <dl className="grid gap-x-6 gap-y-4 p-5 sm:grid-cols-2 lg:grid-cols-3">
          {POLICY.map(([label, value]) => (
            <div key={label}>
              <dt className="text-xs font-medium text-ink/50">{label}</dt>
              <dd className="mt-0.5 text-sm font-medium">{value}</dd>
            </div>
          ))}
        </dl>
      </Card>
    </>
  );
}
