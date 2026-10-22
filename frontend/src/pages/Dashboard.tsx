import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import clsx from 'clsx';
import { ArrowRight, PlayCircle } from 'lucide-react';
import { useLiveMetrics } from '../lib/liveMetrics';
import { useOverview } from '../lib/queries';
import { formatCompact, formatNumber, formatPercent, formatRate } from '../lib/format';
import { Card, CardHeader, PageHeader } from '../components/ui/Card';
import { BarList, Legend, LineChart, Sparkline, type Series } from '../components/ui/Charts';
import { BreakerBadge } from '../components/ui/Badge';
import { ErrorState, Skeleton } from '../components/ui/Feedback';
import { buttonClasses } from '../components/ui/Button';

function Kpi({ label, value, hint, accent, children }: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  accent?: boolean;
  children?: ReactNode;
}) {
  return (
    <Card className="flex flex-col justify-between p-4">
      <div>
        <p className="text-[11.5px] font-semibold uppercase tracking-wider text-ink/50">{label}</p>
        <p className={clsx('mt-1.5 text-[26px] font-semibold tabular-nums tracking-tight', accent && 'text-accent')}>{value}</p>
        {hint && <p className="mt-0.5 text-[12.5px] text-ink/55">{hint}</p>}
      </div>
      {children && <div className="mt-3">{children}</div>}
    </Card>
  );
}

export function Dashboard() {
  const overview = useOverview();
  const live = useLiveMetrics();
  const points = live.points;
  const last = points[points.length - 1];

  const series: Series[] = [
    { key: 'rps', label: 'Requests / s', tone: 'accent', values: points.map((p) => p.requestsPerSec), format: formatRate },
    { key: 'limited', label: 'Rate limited / s', tone: 'ink', values: points.map((p) => p.rateLimitedPerSec), format: formatRate },
    { key: 'errors', label: '5xx / s', tone: 'ink-dashed', values: points.map((p) => p.errorsPerSec), format: formatRate },
  ];

  const data = overview.data;
  const rejectedShare = data && data.requestsToday > 0 ? (data.rejectedToday / data.requestsToday) * 100 : null;
  const idle = points.length > 3 && points.every((p) => p.requestsPerSec === 0);

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Live traffic on this instance and today's totals across the cluster (UTC)."
        actions={
          <Link to="/demos" className={buttonClasses('primary')}>
            <PlayCircle className="h-4 w-4" />
            Run a live demo
          </Link>
        }
      />

      {overview.isError ? (
        <Card>
          <ErrorState error={overview.error} onRetry={() => overview.refetch()} />
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
          {data ? (
            <>
              <Kpi label="Throughput" value={`${formatRate(last?.requestsPerSec ?? 0)}/s`} hint="Live, this instance" accent>
                <Sparkline values={points.map((p) => p.requestsPerSec)} />
              </Kpi>
              <Kpi label="Avg latency" value={last?.avgLatencyMs != null ? `${Math.round(last.avgLatencyMs)} ms` : '–'} hint="Last 2 seconds" />
              <Kpi label="Requests today" value={formatCompact(data.requestsToday)} hint={`${formatNumber(data.activeTenantsToday)} active tenants`} />
              <Kpi label="Rate limited" value={formatCompact(data.rejectedToday)} hint={rejectedShare === null ? 'No traffic yet' : `${formatPercent(rejectedShare)} of traffic`} />
              <Kpi label="Routes" value={formatNumber(data.staticRoutes + Math.max(data.dynamicRoutes, 0))} hint={`${data.staticRoutes} static · ${Math.max(data.dynamicRoutes, 0)} dynamic`} />
              <Kpi
                label="Redis"
                value={data.redis === 'UP' ? 'Healthy' : 'Down'}
                hint={data.redis === 'UP' ? 'Shared state online' : 'Fail-open mode'}
                accent={data.redis === 'UP'}
              />
            </>
          ) : (
            Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[118px] rounded-xl" />)
          )}
        </div>
      )}

      <Card className="mt-5">
        <CardHeader
          title="Live traffic"
          description="Sampled every 2 seconds from this gateway instance."
          actions={<Legend series={series} />}
        />
        <div className="px-3 pb-3 pt-4 sm:px-5">
          <LineChart series={series} height={240} labels={['2 min ago', 'now']} emptyText={live.error ?? 'Collecting samples…'} />
        </div>
        {idle && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink/[0.07] px-5 py-3 text-[13px] text-ink/60">
            No traffic right now. Generate some with a live demo or the API explorer.
            <Link to="/demos" className="inline-flex items-center gap-1 font-medium text-accent hover:underline">
              Open live demos <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </div>
        )}
      </Card>

      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader title="Busiest routes" description="Requests since this instance started; 5xx shown in navy." />
          <div className="px-5 py-5">
            <BarList
              emptyText="No routed traffic yet."
              items={live.topRoutes.map((r) => ({
                label: r.route,
                value: r.requests,
                secondary: r.errors,
                hint: `${formatNumber(r.requests)} req${r.errors ? ` · ${formatNumber(r.errors)} 5xx` : ''}`,
              }))}
            />
          </div>
        </Card>
        <Card>
          <CardHeader
            title="Circuit breakers"
            description="Downstream health as seen by Resilience4j."
            actions={
              <Link to="/resilience" className="text-[13px] font-medium text-accent hover:underline">
                Details
              </Link>
            }
          />
          <ul className="divide-y divide-ink/[0.06]">
            {(data?.circuitBreakers ?? []).map((b) => (
              <li key={b.name} className="flex items-center justify-between gap-3 px-5 py-3.5">
                <div className="min-w-0">
                  <p className="truncate font-mono text-[13px]">{b.name}</p>
                  <p className="text-xs text-ink/50">
                    {b.bufferedCalls} calls in window · failure rate {b.failureRate < 0 ? '–' : formatPercent(b.failureRate)}
                  </p>
                </div>
                <BreakerBadge state={b.state} />
              </li>
            ))}
            {data && data.circuitBreakers.length === 0 && <li className="px-5 py-8 text-center text-[13px] text-ink/50">No circuit breakers.</li>}
            {!data && (
              <li className="p-5">
                <Skeleton className="h-10" />
              </li>
            )}
          </ul>
        </Card>
      </div>
    </>
  );
}
