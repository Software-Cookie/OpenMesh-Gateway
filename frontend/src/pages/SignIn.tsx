import { useState, type FormEvent } from 'react';
import { ArrowRight, Gauge, KeyRound, ShieldCheck, Waypoints } from 'lucide-react';
import { send } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useConsoleConfig } from '../lib/queries';
import type { ApiErrorBody, Tier, TokenResponse } from '../lib/types';
import { Button } from '../components/ui/Button';
import { Field, FormErrors, Input, SegmentedControl, Select, Textarea } from '../components/ui/Form';
import { Logo } from '../components/layout/Logo';

const HIGHLIGHTS = [
  { icon: ShieldCheck, title: 'Zero-trust by default', text: 'RS256 JWT and API key authentication with signed identity headers.' },
  { icon: Gauge, title: 'Distributed rate limits', text: 'Per-tenant token buckets in Redis, shared by every instance.' },
  { icon: Waypoints, title: 'Hot-reload routing', text: 'Change routes at runtime; the whole cluster reloads instantly.' },
  { icon: KeyRound, title: 'Metering and audit', text: 'Usage per tenant for billing and a trail of every admin change.' },
];

export function SignIn() {
  const { signIn, signOutReason } = useAuth();
  const { data: config, isLoading } = useConsoleConfig();
  const devAvailable = Boolean(config?.devIssuerEnabled);
  const [mode, setMode] = useState<'dev' | 'token'>('dev');
  const effectiveMode = devAvailable ? mode : 'token';
  const [showOptions, setShowOptions] = useState(false);
  const [form, setForm] = useState({ tenantId: 'tenant-acme', userId: 'alice', tier: 'PRO' as Tier, scopes: 'read write' });
  const [pasted, setPasted] = useState('');
  const [error, setError] = useState<{ message: string; details?: string[] } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (effectiveMode === 'token') {
      const problem = signIn(pasted);
      if (problem) setError({ message: problem });
      return;
    }
    setBusy(true);
    try {
      const result = await send<TokenResponse & ApiErrorBody>('POST', '/auth/token', {
        credentials: { kind: 'none' },
        body: {
          tenantId: form.tenantId.trim(),
          userId: form.userId.trim(),
          roles: ['ROLE_ADMIN'],
          scopes: form.scopes.trim().split(/\s+/).filter(Boolean),
          tier: form.tier,
          expiresInMinutes: 240,
        },
      });
      if (!result.ok || !result.data?.token) {
        setError({ message: result.data?.message ?? `Could not get a token (HTTP ${result.status})`, details: result.data?.details });
        return;
      }
      const problem = signIn(result.data.token);
      if (problem) setError({ message: problem });
    } catch {
      setError({ message: 'The gateway is not reachable. Is it running?' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <aside className="relative hidden overflow-hidden bg-ink px-12 py-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="pointer-events-none absolute -right-24 -top-24 h-96 w-96 rounded-full border border-white/10" />
        <div className="pointer-events-none absolute -right-8 top-24 h-72 w-72 rounded-full border border-accent/40" />
        <Logo />
        <div className="relative max-w-md">
          <h1 className="text-3xl font-semibold leading-tight tracking-tight">
            One front door for every API your company runs.
          </h1>
          <p className="mt-3 text-[15px] text-white/65">
            Authenticate, rate limit, route and observe multi-tenant traffic from a single control plane.
          </p>
          <ul className="mt-10 space-y-5">
            {HIGHLIGHTS.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-3.5">
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-white/[0.08] text-accent">
                  <Icon className="h-[18px] w-[18px]" />
                </span>
                <div>
                  <p className="text-sm font-semibold">{title}</p>
                  <p className="text-[13px] text-white/60">{text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-white/40">Spring Cloud Gateway · Redis · Resilience4j · React</p>
      </aside>

      <main className="flex items-center justify-center bg-canvas px-5 py-12">
        <div className="w-full max-w-md">
          <div className="mb-8 lg:hidden">
            <div className="inline-flex rounded-xl bg-ink px-3 py-2">
              <Logo />
            </div>
          </div>
          <h2 className="text-2xl font-semibold tracking-tight">Sign in to the console</h2>
          <p className="mt-1 text-sm text-ink/60">Administrators only. Your token needs the ROLE_ADMIN role.</p>

          {signOutReason && (
            <div className="mt-5 rounded-lg bg-accent/10 px-3.5 py-2.5 text-[13px] font-medium text-accent-strong">{signOutReason}</div>
          )}

          <form onSubmit={submit} className="mt-6 space-y-5 rounded-2xl bg-white p-6 shadow-card ring-1 ring-ink/[0.07]">
            <SegmentedControl
              className="w-full [&>button]:flex-1"
              value={effectiveMode}
              onChange={(value) => {
                setMode(value);
                setError(null);
              }}
              options={[
                { value: 'dev', label: 'Development token', disabled: !devAvailable },
                { value: 'token', label: 'Identity provider token' },
              ]}
            />

            {effectiveMode === 'dev' ? (
              <div className="space-y-4">
                <p className="text-[13px] text-ink/60">
                  Mints a short-lived RS256 admin token with the gateway's built-in development issuer.
                </p>
                {showOptions ? (
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field label="Tenant">
                      {(id) => <Input id={id} value={form.tenantId} onChange={(e) => setForm({ ...form, tenantId: e.target.value })} />}
                    </Field>
                    <Field label="User">
                      {(id) => <Input id={id} value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })} />}
                    </Field>
                    <Field label="Plan tier">
                      {(id) => (
                        <Select id={id} value={form.tier} onChange={(e) => setForm({ ...form, tier: e.target.value as Tier })}>
                          <option>FREE</option>
                          <option>PRO</option>
                          <option>ENTERPRISE</option>
                        </Select>
                      )}
                    </Field>
                    <Field label="Scopes">
                      {(id) => <Input id={id} value={form.scopes} onChange={(e) => setForm({ ...form, scopes: e.target.value })} />}
                    </Field>
                  </div>
                ) : (
                  <button type="button" onClick={() => setShowOptions(true)} className="text-[13px] font-medium text-accent hover:underline">
                    Customise tenant, user and tier
                  </button>
                )}
              </div>
            ) : (
              <Field label="Access token" hint="paste a JWT">
                {(id) => (
                  <Textarea
                    id={id}
                    rows={5}
                    mono
                    value={pasted}
                    onChange={(e) => setPasted(e.target.value)}
                    placeholder="eyJhbGciOiJSUzI1NiIs…"
                  />
                )}
              </Field>
            )}

            <FormErrors message={error?.message} details={error?.details} />

            <Button type="submit" variant="primary" className="w-full" loading={busy || isLoading} icon={<ArrowRight className="h-4 w-4" />}>
              {effectiveMode === 'dev' ? 'Sign in as administrator' : 'Sign in with token'}
            </Button>
          </form>

          {!isLoading && !devAvailable && (
            <p className="mt-4 text-center text-xs text-ink/50">
              The development issuer is off (production mode). Get a token from your identity provider.
            </p>
          )}
        </div>
      </main>
    </div>
  );
}
