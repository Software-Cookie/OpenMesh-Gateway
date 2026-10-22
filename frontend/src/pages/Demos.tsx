import { Info } from 'lucide-react';
import { PageHeader } from '../components/ui/Card';
import { ApiKeyDemo } from '../scenarios/ApiKeyDemo';
import { CircuitBreakerDemo } from '../scenarios/CircuitBreakerDemo';
import { DynamicRouteDemo } from '../scenarios/DynamicRouteDemo';
import { RateLimitDemo } from '../scenarios/RateLimitDemo';
import { ZeroTrustDemo } from '../scenarios/ZeroTrustDemo';

export function DemosPage() {
  return (
    <>
      <PageHeader
        title="Live demos"
        description="One-click scenarios that exercise the real gateway, Redis and downstream services, then explain what happened. Each demo cleans up after itself."
      />
      <div className="mb-5 flex items-start gap-3 rounded-xl bg-white p-4 text-[13px] text-ink/65 shadow-card ring-1 ring-ink/[0.07]">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-accent" />
        <p>
          These demos use the sample services from the Docker Compose stack (<span className="font-mono">service-alpha</span> echoes what it
          received, <span className="font-mono">service-beta</span> can fail on demand). Watch the Dashboard and Audit log while they run.
        </p>
      </div>
      <div className="grid gap-5 xl:grid-cols-2">
        <ZeroTrustDemo />
        <RateLimitDemo />
        <CircuitBreakerDemo />
        <DynamicRouteDemo />
        <ApiKeyDemo />
      </div>
    </>
  );
}
