import { useState } from 'react';
import { KeyRound } from 'lucide-react';
import { api, send } from '../lib/api';
import { randomSuffix } from '../lib/format';
import type { CreatedApiKey } from '../lib/types';
import { Badge } from '../components/ui/Badge';
import type { EchoResponse } from './ZeroTrustDemo';
import { ScenarioCard } from './ScenarioCard';
import { StepFailure, useSteps } from './useSteps';

export function ApiKeyDemo() {
  const [facts, setFacts] = useState<{ label: string; value: string }[]>([]);
  const { steps, running, outcome, run } = useSteps([
    'Issue an ENTERPRISE API key for a partner tenant',
    'Partner calls an API with the key',
    'Revoke the key',
    'The same key is rejected immediately',
  ]);

  const start = () =>
    run(async (step) => {
      setFacts([]);
      const tenant = `partner-${randomSuffix()}`;
      const created = await step(
        0,
        () => api.post<CreatedApiKey>('/admin/v1/api-keys', { name: 'console-demo', tenantId: tenant, tier: 'ENTERPRISE', scopes: ['read'] }),
        (k) => `${k.record.prefix}… (only the SHA-256 hash is stored)`,
      );
      await step(
        1,
        async () => {
          const r = await send<EchoResponse>('GET', '/service-alpha/orders', { credentials: { kind: 'apiKey', key: created.apiKey } });
          if (!r.ok) throw new StepFailure(`Expected 200, got HTTP ${r.status}`);
          const next = [
            { label: 'Plan applied', value: r.headers.get('x-ratelimit-tier') ?? '–' },
            { label: 'Burst limit', value: r.headers.get('x-ratelimit-limit') ?? '–' },
          ];
          if (r.data?.identity) {
            next.push({ label: 'Tenant seen by service', value: r.data.identity.tenantId ?? '–' });
            next.push({ label: 'Key forwarded downstream', value: r.data.apiKeyForwarded ? 'yes' : 'no' });
          }
          setFacts(next);
          return r;
        },
        (r) => `200 OK in ${r.durationMs} ms`,
      );
      await step(2, () => api.delete(`/admin/v1/api-keys/${encodeURIComponent(created.record.id)}`), () => 'Revocation recorded in the audit log');
      await step(
        3,
        async () => {
          const r = await send('GET', '/service-alpha/orders', { credentials: { kind: 'apiKey', key: created.apiKey } });
          if (r.status !== 401) throw new StepFailure(`Expected 401, got HTTP ${r.status}`);
          return r;
        },
        () => '401 Unauthorized: "API key is invalid or revoked"',
      );
    });

  return (
    <ScenarioCard
      icon={<KeyRound className="h-5 w-5" />}
      title="API key lifecycle"
      proves="Machine credentials carry a tenant and plan, are never forwarded to services, and revoke instantly."
      steps={steps}
      running={running}
      outcome={outcome}
      onRun={start}
      successText="Key issued, used and revoked. Revocation took effect on the very next request."
    >
      {facts.length > 0 && (
        <dl className="grid grid-cols-2 gap-3">
          {facts.map((fact) => (
            <div key={fact.label} className="rounded-lg bg-ink/[0.03] px-3 py-2 ring-1 ring-inset ring-ink/[0.06]">
              <dt className="text-[11px] text-ink/50">{fact.label}</dt>
              <dd className="mt-0.5">
                <Badge tone={fact.value === 'no' || fact.value === 'ENTERPRISE' ? 'accent' : 'muted'} mono>
                  {fact.value}
                </Badge>
              </dd>
            </div>
          ))}
        </dl>
      )}
    </ScenarioCard>
  );
}
