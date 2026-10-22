import { useMemo, useState } from 'react';
import clsx from 'clsx';
import { KeyRound, RefreshCw, ScrollText, Waypoints } from 'lucide-react';
import { formatDateTime, formatTime } from '../lib/format';
import { useAudit } from '../lib/queries';
import type { AuditEvent } from '../lib/types';
import { Card, PageHeader } from '../components/ui/Card';
import { Badge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { EmptyState, ErrorState, TableSkeleton } from '../components/ui/Feedback';
import { SegmentedControl } from '../components/ui/Form';

type Filter = 'all' | 'routes' | 'keys';

function describe(event: AuditEvent): string {
  const words = event.action.toLowerCase().split('_');
  return words.join(' ').replace(/^\w/, (c) => c.toUpperCase());
}

function groupByDay(events: AuditEvent[]) {
  const groups = new Map<string, AuditEvent[]>();
  for (const event of events) {
    const day = new Date(event.timestamp).toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
    groups.set(day, [...(groups.get(day) ?? []), event]);
  }
  return [...groups.entries()];
}

export function AuditPage() {
  const audit = useAudit();
  const [filter, setFilter] = useState<Filter>('all');
  const events = useMemo(
    () =>
      (audit.data ?? []).filter((e) =>
        filter === 'all' ? true : filter === 'routes' ? e.action.startsWith('ROUTE') : e.action.startsWith('API_KEY'),
      ),
    [audit.data, filter],
  );

  return (
    <>
      <PageHeader
        title="Audit log"
        description="Every change made through the admin API, newest first. The last 1,000 events are kept in Redis."
        actions={
          <>
            <SegmentedControl
              value={filter}
              onChange={setFilter}
              options={[
                { value: 'all', label: 'All' },
                { value: 'routes', label: 'Routes' },
                { value: 'keys', label: 'API keys' },
              ]}
            />
            <Button icon={<RefreshCw className="h-4 w-4" />} onClick={() => audit.refetch()} loading={audit.isFetching}>
              Refresh
            </Button>
          </>
        }
      />

      <Card>
        {audit.isLoading && <TableSkeleton />}
        {audit.isError && <ErrorState error={audit.error} onRetry={() => audit.refetch()} />}
        {audit.data && events.length === 0 && (
          <EmptyState icon={<ScrollText className="h-5 w-5" />} title="No activity yet" description="Route and API key changes will appear here." />
        )}
        {events.length > 0 && (
          <div className="divide-y divide-ink/[0.06]">
            {groupByDay(events).map(([day, items]) => (
              <section key={day} className="px-5 py-4">
                <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-ink/45">{day}</h3>
                <ol className="relative space-y-4 border-l border-ink/10 pl-6">
                  {items.map((event, index) => {
                    const destructive = /DELETED|REVOKED/.test(event.action);
                    const Icon = event.action.startsWith('API_KEY') ? KeyRound : Waypoints;
                    return (
                      <li key={`${event.timestamp}-${index}`} className="relative">
                        <span
                          className={clsx(
                            'absolute -left-[34px] top-0.5 grid h-5 w-5 place-items-center rounded-full ring-4 ring-white',
                            destructive ? 'bg-ink text-white' : 'bg-accent text-white',
                          )}
                        >
                          <Icon className="h-3 w-3" />
                        </span>
                        <div className="flex flex-wrap items-baseline justify-between gap-2">
                          <p className="text-sm">
                            <span className="font-semibold">{event.actor}</span>{' '}
                            <span className="text-ink/60">{describe(event).toLowerCase()}</span>{' '}
                            <span className="font-mono text-[13px]">{event.target}</span>
                          </p>
                          <time className="text-xs text-ink/45" title={formatDateTime(event.timestamp)}>
                            {formatTime(event.timestamp)}
                          </time>
                        </div>
                        {event.detail && (
                          <div className="mt-1">
                            <Badge mono tone="muted">{event.detail}</Badge>
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ol>
              </section>
            ))}
          </div>
        )}
      </Card>
    </>
  );
}
