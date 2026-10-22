import { useState } from 'react';
import clsx from 'clsx';
import { Gauge } from 'lucide-react';
import { api, send } from '../lib/api';
import { randomSuffix } from '../lib/format';
import type { CreatedApiKey } from '../lib/types';
import { Badge } from '../components/ui/Badge';
import { Select } from '../components/ui/Form';
import { ScenarioCard } from './ScenarioCard';
import { StepFailure, useSteps } from './useSteps';

type Cell = number | null;

export function RateLimitDemo() {
  const [count, setCount] = useState(30);
  const [cells, setCells] = useState<Cell[]>([]);
  const [retryAfter, setRetryAfter] = useState<string | null>(null);
  const { steps, running, outcome, run } = useSteps([
    'Create a FREE-plan API key (burst 10, then 5 req/s)',
    `Fire ${count} requests at the same moment`,
    'Clean up: revoke the demo key',
  ]);

  const allowed = cells.filter((c) => c !== null && c >= 200 && c < 300).length;
  const limited = cells.filter((c) => c === 429).length;

  const start = () =>
    run(async (step) => {
      setCells([]);
      setRetryAfter(null);
      const created = await step(
        0,
        () => api.post<CreatedApiKey>('/admin/v1/api-keys', {
          name: 'console-demo-rate-limit',
          tenantId: `demo-free-${randomSuffix()}`,
          tier: 'FREE',
          scopes: ['read'],
        }),
        (key) => `Tenant ${key.record.tenantId}, key ${key.record.prefix}…`,
      );
      try {
        await step(
          1,
          async () => {
            setCells(Array<Cell>(count).fill(null));
            const statuses = await Promise.all(
              Array.from({ length: count }, (_, i) =>
                send('GET', '/service-alpha/orders', { credentials: { kind: 'apiKey', key: created.apiKey } })
                  .then((r) => {
                    if (r.status === 429) setRetryAfter(r.headers.get('retry-after'));
                    return r.status;
                  })
                  .catch(() => 0)
                  .then((status) => {
                    setCells((current) => current.map((c, index) => (index === i ? status : c)));
                    return status;
                  }),
              ),
            );
            const rejected = statuses.filter((s) => s === 429).length;
            if (rejected === 0) throw new StepFailure('No request was rate limited (is Redis running?)');
            return { ok: statuses.filter((s) => s >= 200 && s < 300).length, rejected };
          },
          (r) => `${r.ok} served, ${r.rejected} rejected with 429 Too Many Requests`,
        );
      } finally {
        await step(2, () => api.delete(`/admin/v1/api-keys/${encodeURIComponent(created.record.id)}`), () => 'Key revoked');
      }
    });

  return (
    <ScenarioCard
      icon={<Gauge className="h-5 w-5" />}
      title="Distributed rate limiting"
      proves="Each tenant gets a token bucket in Redis sized by its plan, shared by every gateway instance."
      steps={steps}
      running={running}
      outcome={outcome}
      onRun={start}
      successText={`The bucket held ${allowed} requests and rejected the rest${retryAfter ? `, telling the client to retry after ${retryAfter}s` : ''}.`}
      controls={
        <label className="flex items-center gap-2 text-[13px] text-ink/60">
          Requests
          <Select value={count} onChange={(e) => setCount(Number(e.target.value))} disabled={running} className="h-8 w-20 py-1">
            {[20, 30, 50].map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </Select>
        </label>
      }
    >
      {cells.length > 0 && (
        <div>
          <div className="mb-2 flex flex-wrap gap-1.5">
            <Badge tone="accent">{allowed} allowed</Badge>
            <Badge tone="ink">{limited} rate limited</Badge>
          </div>
          <div className="grid grid-cols-10 gap-1">
            {cells.map((status, i) => (
              <div
                key={i}
                title={status === null ? 'pending' : `HTTP ${status}`}
                className={clsx(
                  'grid h-7 place-items-center rounded font-mono text-[10px] transition-colors duration-200',
                  status === null && 'animate-pulse bg-ink/[0.06] text-transparent',
                  status !== null && status >= 200 && status < 300 && 'bg-accent text-white',
                  status === 429 && 'bg-ink text-white',
                  status !== null && status !== 429 && (status < 200 || status >= 300) && 'bg-white text-ink ring-1 ring-ink/30',
                )}
              >
                {status ?? ''}
              </div>
            ))}
          </div>
        </div>
      )}
    </ScenarioCard>
  );
}
