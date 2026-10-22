import { useMemo, useState, type FormEvent } from 'react';
import { Lock, Pencil, Plus, RefreshCw, Search, Trash2, Waypoints } from 'lucide-react';
import { ApiError } from '../lib/api';
import { scopeList } from '../lib/format';
import { useDeleteRoute, useReloadRoutes, useRoutes, useSaveRoute } from '../lib/queries';
import type { RouteDefinition } from '../lib/types';
import { Card, PageHeader } from '../components/ui/Card';
import { Badge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { EmptyState, ErrorState, TableSkeleton } from '../components/ui/Feedback';
import { Field, FormErrors, Input, Textarea } from '../components/ui/Form';
import { ConfirmDialog, Drawer } from '../components/ui/Overlay';
import { Table, Td, Th, Tr } from '../components/ui/Table';
import { useToast } from '../components/ui/Toast';

interface RouteForm {
  id: string;
  uri: string;
  predicates: string;
  filters: string;
  scopes: string;
  rate: string;
  burst: string;
  order: string;
}

const EMPTY_FORM: RouteForm = {
  id: '',
  uri: '',
  predicates: 'Path=/orders/**',
  filters: 'StripPrefix=1',
  scopes: '',
  rate: '',
  burst: '',
  order: '0',
};

function toForm(route: RouteDefinition): RouteForm {
  const rl = route.metadata?.rateLimit;
  return {
    id: route.id,
    uri: route.uri,
    predicates: route.predicates.join('\n'),
    filters: route.filters.join('\n'),
    scopes: scopeList(route.metadata?.requiredScopes).join(' '),
    rate: rl?.replenishRate != null ? String(rl.replenishRate) : '',
    burst: rl?.burstCapacity != null ? String(rl.burstCapacity) : '',
    order: String(route.order ?? 0),
  };
}

function fromForm(form: RouteForm): Omit<RouteDefinition, 'source'> {
  const lines = (value: string) => value.split('\n').map((s) => s.trim()).filter(Boolean);
  const metadata: RouteDefinition['metadata'] = {};
  if (form.scopes.trim()) metadata.requiredScopes = form.scopes.trim();
  if (form.rate || form.burst) {
    metadata.rateLimit = {};
    if (form.rate) metadata.rateLimit.replenishRate = Number(form.rate);
    if (form.burst) metadata.rateLimit.burstCapacity = Number(form.burst);
  }
  return {
    id: form.id.trim(),
    uri: form.uri.trim(),
    predicates: lines(form.predicates),
    filters: lines(form.filters),
    order: Number(form.order || 0),
    metadata,
  };
}

function RouteEditor({ route, open, onClose }: { route: RouteDefinition | null; open: boolean; onClose: () => void }) {
  const [form, setForm] = useState<RouteForm>(route ? toForm(route) : EMPTY_FORM);
  const save = useSaveRoute();
  const toast = useToast();
  const error = save.error instanceof ApiError ? save.error : null;
  const set = (key: keyof RouteForm) => (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value });

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const saved = await save.mutateAsync({ route: fromForm(form), existingId: route?.id });
      toast.success(`Route ${saved.id} published to every gateway instance.`);
      onClose();
    } catch {
      /* errors are rendered from save.error */
    }
  }

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={route ? `Edit ${route.id}` : 'New route'}
      description="Saved to Redis and hot-reloaded on all instances. The gateway validates every field before saving."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="route-form" loading={save.isPending}>
            {route ? 'Save changes' : 'Create route'}
          </Button>
        </>
      }
    >
      <form id="route-form" onSubmit={submit} className="space-y-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Route ID">
            {(id) => <Input id={id} mono value={form.id} onChange={set('id')} disabled={Boolean(route)} placeholder="orders-service" required />}
          </Field>
          <Field label="Order" hint="lower runs first">
            {(id) => <Input id={id} type="number" value={form.order} onChange={set('order')} />}
          </Field>
        </div>
        <Field label="Target URI">
          {(id) => <Input id={id} mono value={form.uri} onChange={set('uri')} placeholder="http://orders-service:8080" required />}
        </Field>
        <Field label="Predicates" hint="one per line">
          {(id) => <Textarea id={id} mono rows={3} value={form.predicates} onChange={set('predicates')} />}
        </Field>
        <Field label="Filters" hint="one per line">
          {(id) => <Textarea id={id} mono rows={3} value={form.filters} onChange={set('filters')} />}
        </Field>

        <div className="rounded-xl bg-ink/[0.03] p-4 ring-1 ring-inset ring-ink/[0.06]">
          <p className="mb-3 text-[13px] font-semibold">Policy</p>
          <div className="space-y-4">
            <Field label="Required scopes" hint="space separated, empty = any authenticated caller">
              {(id) => <Input id={id} mono value={form.scopes} onChange={set('scopes')} placeholder="orders:read" />}
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Anonymous rate" hint="req/s">
                {(id) => <Input id={id} type="number" min="0" step="any" value={form.rate} onChange={set('rate')} placeholder="default 5" />}
              </Field>
              <Field label="Anonymous burst">
                {(id) => <Input id={id} type="number" min="0" step="any" value={form.burst} onChange={set('burst')} placeholder="default 10" />}
              </Field>
            </div>
            <p className="text-xs text-ink/50">Authenticated callers are always limited by their plan tier.</p>
          </div>
        </div>

        <FormErrors message={error?.message ?? (save.error ? String(save.error) : null)} details={error?.details} />
      </form>
    </Drawer>
  );
}

export function RoutesPage() {
  const routes = useRoutes();
  const reload = useReloadRoutes();
  const remove = useDeleteRoute();
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<{ route: RouteDefinition | null } | null>(null);
  const [deleting, setDeleting] = useState<RouteDefinition | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (routes.data ?? []).filter(
      (r) => !q || r.id.toLowerCase().includes(q) || r.uri.toLowerCase().includes(q) || r.predicates.join(' ').toLowerCase().includes(q),
    );
  }, [routes.data, query]);

  const dynamicCount = routes.data?.filter((r) => r.source === 'dynamic').length ?? 0;

  return (
    <>
      <PageHeader
        title="Routes"
        description="Static routes come from application.yml and are read-only. Dynamic routes live in Redis and reload on every instance without a restart."
        actions={
          <>
            <Button
              icon={<RefreshCw className="h-4 w-4" />}
              loading={reload.isPending}
              onClick={() => reload.mutate(undefined, { onSuccess: () => toast.success('Reload broadcast to all gateway instances.') })}
            >
              Reload cluster
            </Button>
            <Button variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setEditing({ route: null })}>
              New route
            </Button>
          </>
        }
      />

      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink/[0.07] px-5 py-3">
          <div className="relative w-full max-w-xs">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/40" />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search routes" className="pl-9" aria-label="Search routes" />
          </div>
          {routes.data && (
            <p className="text-[13px] text-ink/55">
              {routes.data.length} routes · {dynamicCount} dynamic
            </p>
          )}
        </div>

        {routes.isLoading && <TableSkeleton />}
        {routes.isError && <ErrorState error={routes.error} onRetry={() => routes.refetch()} />}
        {routes.data && filtered.length === 0 && (
          <EmptyState
            icon={<Waypoints className="h-5 w-5" />}
            title={query ? 'No routes match your search' : 'No routes yet'}
            description="Create a dynamic route to expose a new service through the gateway."
          />
        )}
        {filtered.length > 0 && (
          <Table>
            <thead>
              <tr>
                <Th>Route</Th>
                <Th>Target</Th>
                <Th>Match</Th>
                <Th>Filters</Th>
                <Th>Policy</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((route) => {
                const scopes = scopeList(route.metadata?.requiredScopes);
                const rl = route.metadata?.rateLimit;
                return (
                  <Tr key={route.id}>
                    <Td>
                      <p className="whitespace-nowrap font-mono font-medium">{route.id}</p>
                      <div className="mt-1">
                        {route.source === 'dynamic' ? (
                          <Badge tone="accent">dynamic</Badge>
                        ) : (
                          <Badge tone="muted">
                            <Lock className="h-3 w-3" /> static
                          </Badge>
                        )}
                      </div>
                    </Td>
                    <Td className="whitespace-nowrap font-mono text-[12.5px] text-ink/75">{route.uri}</Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {route.predicates.map((p) => (
                          <Badge key={p} mono tone="muted">{p}</Badge>
                        ))}
                      </div>
                    </Td>
                    <Td className="max-w-[260px]">
                      <div className="flex flex-wrap gap-1">
                        {route.filters.length === 0 && <span className="text-ink/40">none</span>}
                        {route.filters.map((f) => (
                          <Badge key={f} mono tone="muted" title={f} className="max-w-[240px]">
                            {f.length > 34 ? `${f.slice(0, 33)}…` : f}
                          </Badge>
                        ))}
                      </div>
                    </Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {scopes.length ? scopes.map((s) => <Badge key={s} tone="outline" mono>{s}</Badge>) : <Badge tone="muted">any caller</Badge>}
                        {rl && (rl.replenishRate != null || rl.burstCapacity != null) && (
                          <Badge mono tone="muted">anon {rl.replenishRate ?? '–'}/s · {rl.burstCapacity ?? '–'}</Badge>
                        )}
                      </div>
                    </Td>
                    <Td>
                      {route.source === 'dynamic' && (
                        <div className="flex justify-end gap-1">
                          <Button size="sm" variant="ghost" aria-label={`Edit ${route.id}`} onClick={() => setEditing({ route })}>
                            <Pencil className="h-3.5 w-3.5" />
                          </Button>
                          <Button size="sm" variant="ghost" aria-label={`Delete ${route.id}`} onClick={() => setDeleting(route)}>
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      )}
                    </Td>
                  </Tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>

      {editing && <RouteEditor key={editing.route?.id ?? 'new'} route={editing.route} open onClose={() => setEditing(null)} />}

      <ConfirmDialog
        open={Boolean(deleting)}
        title={`Delete ${deleting?.id}?`}
        description="Traffic to this route stops immediately on every gateway instance. This is recorded in the audit log."
        confirmLabel="Delete route"
        loading={remove.isPending}
        onClose={() => setDeleting(null)}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`Route ${deleting.id} deleted.`);
              setDeleting(null);
            },
            onError: (e) => toast.error(e.message),
          })
        }
      />
    </>
  );
}
