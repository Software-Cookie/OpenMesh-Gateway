import { useState } from 'react';
import { Waypoints } from 'lucide-react';
import { api, send } from '../lib/api';
import { randomSuffix } from '../lib/format';
import type { RouteDefinition } from '../lib/types';
import { ScenarioCard } from './ScenarioCard';
import { poll, useSteps } from './useSteps';

export function DynamicRouteDemo() {
  const [liveAfterMs, setLiveAfterMs] = useState<number | null>(null);
  const [path, setPath] = useState<string | null>(null);
  const { steps, running, outcome, run } = useSteps([
    'Create a new route through the admin API',
    'Call it: every instance reloaded without a restart',
    'Delete the route',
    'Traffic to it stops (404)',
  ]);

  const start = () =>
    run(async (step) => {
      setLiveAfterMs(null);
      const id = `demo-${randomSuffix()}`;
      setPath(`/${id}/hello`);
      const routes = await api.get<RouteDefinition[]>('/admin/v1/routes');
      const target = routes.find((r) => r.id === 'alpha-service-route')?.uri ?? 'http://downstream-service-alpha:8081';
      const createdAt = performance.now();

      await step(
        0,
        () => api.post<RouteDefinition>('/admin/v1/routes', {
          id,
          uri: target,
          predicates: [`Path=/${id}/**`],
          filters: ['StripPrefix=1'],
          order: 0,
          metadata: {},
        }),
        (route) => `${route.predicates[0]} → ${route.uri}`,
      );
      try {
        await step(
          1,
          async () => {
            await poll(async () => ((await send('GET', `/${id}/hello`)).status === 200 ? true : null), 10_000, 100);
            const ms = Math.round(performance.now() - createdAt);
            setLiveAfterMs(ms);
            return ms;
          },
          (ms) => `GET /${id}/hello returned 200, live ${ms} ms after creation`,
        );
      } finally {
        await step(2, () => api.delete(`/admin/v1/routes/${id}`), () => 'Removed from Redis and broadcast to all instances');
      }
      await step(
        3,
        () => poll(async () => ((await send('GET', `/${id}/hello`)).status === 404 ? true : null), 10_000, 100),
        () => 'GET returns 404 Not Found',
      );
    });

  return (
    <ScenarioCard
      icon={<Waypoints className="h-5 w-5" />}
      title="Hot-reload routing"
      proves="Routes change at runtime. Redis Pub/Sub tells every instance to reload instantly."
      steps={steps}
      running={running}
      outcome={outcome}
      onRun={start}
      successText={liveAfterMs !== null ? `Route went live in ${liveAfterMs} ms and was removed cleanly. Zero restarts.` : undefined}
    >
      {path && (
        <div className="flex items-center justify-between gap-3 rounded-lg bg-ink/[0.03] px-3.5 py-3 ring-1 ring-inset ring-ink/[0.06]">
          <span className="truncate font-mono text-[12.5px]">{path}</span>
          {liveAfterMs !== null && (
            <span className="shrink-0 text-right">
              <span className="block text-xl font-semibold tabular-nums text-accent">{liveAfterMs} ms</span>
              <span className="block text-[11px] text-ink/50">to go live</span>
            </span>
          )}
        </div>
      )}
    </ScenarioCard>
  );
}
