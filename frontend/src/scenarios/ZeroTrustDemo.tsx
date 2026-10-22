import { useState } from 'react';
import clsx from 'clsx';
import { ShieldCheck } from 'lucide-react';
import { send } from '../lib/api';
import { useAuth } from '../lib/auth';
import { ScenarioCard } from './ScenarioCard';
import { StepFailure, useSteps } from './useSteps';

/** Response of the demo "alpha" echo service. */
export interface EchoResponse {
  identity?: { userId?: string; tenantId?: string; roles?: string; scopes?: string; authMethod?: string };
  signature?: { valid: boolean; reason: string };
  apiKeyForwarded?: boolean;
}

const FORGED = {
  'X-User-Id': 'attacker',
  'X-Tenant-Id': 'victim-corp',
  'X-User-Roles': 'ROLE_ADMIN',
  'X-Gateway-Signature': 'v1=forged-signature',
};

export function ZeroTrustDemo() {
  const { claims } = useAuth();
  const { steps, running, outcome, run } = useSteps([
    'Call a protected API with forged identity headers',
    'Gateway replaces them with the verified identity from the JWT',
    'Service verifies the gateway HMAC signature',
  ]);
  const [echo, setEcho] = useState<EchoResponse | null>(null);

  const start = () =>
    run(async (step) => {
      setEcho(null);
      const result = await step(
        0,
        async () => {
          const response = await send<EchoResponse>('GET', '/service-alpha/orders', { headers: FORGED });
          if (!response.ok) throw new StepFailure(`Gateway answered HTTP ${response.status}`);
          if (!response.data?.identity) throw new StepFailure('Downstream is not the demo echo service, so headers cannot be inspected');
          return response.data;
        },
        () => 'Sent X-User-Id: attacker and X-Tenant-Id: victim-corp',
      );
      setEcho(result);
      await step(
        1,
        async () => {
          if (result.identity?.userId !== claims?.sub) throw new StepFailure(`Service received user "${result.identity?.userId}"`);
          return result.identity;
        },
        (identity) => `Service saw user "${identity?.userId}" in tenant "${identity?.tenantId}"`,
      );
      await step(
        2,
        async () => {
          if (!result.signature?.valid) throw new StepFailure(`Signature check failed: ${result.signature?.reason ?? 'missing'}`);
          return result.signature;
        },
        () => 'HMAC-SHA256 signature valid: the request provably came through the gateway',
      );
    });

  const rows: [string, string, string | undefined][] = [
    ['User', FORGED['X-User-Id'], echo?.identity?.userId],
    ['Tenant', FORGED['X-Tenant-Id'], echo?.identity?.tenantId],
    ['Roles', FORGED['X-User-Roles'], echo?.identity?.roles],
    ['Signature', 'forged', echo?.signature ? (echo.signature.valid ? 'valid HMAC' : 'invalid') : undefined],
  ];

  return (
    <ScenarioCard
      icon={<ShieldCheck className="h-5 w-5" />}
      title="Zero-trust identity"
      proves="Callers cannot impersonate other users or tenants by sending identity headers."
      steps={steps}
      running={running}
      outcome={outcome}
      onRun={start}
      successText="Spoofing blocked. Services only ever see the identity the gateway verified and signed."
    >
      {echo && (
        <div className="overflow-hidden rounded-lg ring-1 ring-ink/[0.08]">
          <div className="grid grid-cols-[80px_minmax(0,1fr)_minmax(0,1fr)] bg-ink/[0.03] px-3 py-2 text-[11px] font-semibold uppercase tracking-wider text-ink/50">
            <span />
            <span>Client sent</span>
            <span>Service received</span>
          </div>
          {rows.map(([label, sent, received]) => (
            <div key={label} className="grid grid-cols-[80px_minmax(0,1fr)_minmax(0,1fr)] items-center border-t border-ink/[0.06] px-3 py-2 text-[12.5px]">
              <span className="text-ink/55">{label}</span>
              <span className="truncate font-mono text-ink/45 line-through">{sent}</span>
              <span className={clsx('truncate font-mono font-medium', received ? 'text-accent-strong' : 'text-ink/40')}>{received ?? '–'}</span>
            </div>
          ))}
        </div>
      )}
    </ScenarioCard>
  );
}
