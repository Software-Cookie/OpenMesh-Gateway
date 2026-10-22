import { useState, type FormEvent } from 'react';
import { KeyRound, Plus, ShieldCheck } from 'lucide-react';
import { ApiError } from '../lib/api';
import { formatDateTime, relativeTime } from '../lib/format';
import { useApiKeys, useCreateApiKey, useRevokeApiKey } from '../lib/queries';
import type { ApiKeyRecord, CreatedApiKey, Tier } from '../lib/types';
import { Card, PageHeader } from '../components/ui/Card';
import { Badge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CodeBlock';
import { EmptyState, ErrorState, TableSkeleton } from '../components/ui/Feedback';
import { Field, FormErrors, Input, Select } from '../components/ui/Form';
import { ConfirmDialog, Modal } from '../components/ui/Overlay';
import { Table, Td, Th, Tr } from '../components/ui/Table';
import { useToast } from '../components/ui/Toast';

const TIERS: { value: Tier; label: string }[] = [
  { value: 'FREE', label: 'FREE · 5 req/s, burst 10' },
  { value: 'PRO', label: 'PRO · 50 req/s, burst 100' },
  { value: 'ENTERPRISE', label: 'ENTERPRISE · 500 req/s, burst 1000' },
];

function CreateKeyModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const create = useCreateApiKey();
  const [form, setForm] = useState({ name: '', tenantId: '', tier: 'PRO' as Tier, scopes: 'read' });
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const error = create.error instanceof ApiError ? create.error : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const result = await create.mutateAsync({
        name: form.name.trim(),
        tenantId: form.tenantId.trim(),
        tier: form.tier,
        scopes: form.scopes.trim().split(/\s+/).filter(Boolean),
      });
      setCreated(result);
    } catch {
      /* shown below */
    }
  }

  if (created) {
    return (
      <Modal
        open={open}
        onClose={onClose}
        title="Your new API key"
        description="Copy it now. Only a hash is stored, so it cannot be shown again."
        footer={<Button variant="primary" onClick={onClose}>Done</Button>}
      >
        <div className="rounded-xl bg-accent/[0.07] p-4 ring-1 ring-inset ring-accent/20">
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded-lg bg-white px-3 py-2 font-mono text-[13px] ring-1 ring-accent/20">
              {created.apiKey}
            </code>
            <CopyButton value={created.apiKey} className="bg-white ring-1 ring-ink/10" />
          </div>
          <dl className="mt-4 grid grid-cols-3 gap-3 text-[13px]">
            <div>
              <dt className="text-ink/50">Tenant</dt>
              <dd className="font-mono">{created.record.tenantId}</dd>
            </div>
            <div>
              <dt className="text-ink/50">Tier</dt>
              <dd>{created.record.tier}</dd>
            </div>
            <div>
              <dt className="text-ink/50">Scopes</dt>
              <dd className="font-mono">{created.record.scopes.join(' ') || 'none'}</dd>
            </div>
          </dl>
        </div>
        <p className="mt-4 text-[13px] text-ink/60">
          Send it in the <span className="font-mono">X-API-Key</span> header. The gateway removes it before forwarding requests.
        </p>
      </Modal>
    );
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Create API key"
      description="For machine-to-machine clients. The key is bound to one tenant, plan and set of scopes."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="key-form" loading={create.isPending}>
            Create key
          </Button>
        </>
      }
    >
      <form id="key-form" onSubmit={submit} className="space-y-4">
        <Field label="Name" hint="who or what uses it">
          {(id) => <Input id={id} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="billing-batch-job" required />}
        </Field>
        <Field label="Tenant ID">
          {(id) => <Input id={id} mono value={form.tenantId} onChange={(e) => setForm({ ...form, tenantId: e.target.value })} placeholder="tenant-acme" required />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Plan tier">
            {(id) => (
              <Select id={id} value={form.tier} onChange={(e) => setForm({ ...form, tier: e.target.value as Tier })}>
                {TIERS.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Scopes" hint="space separated">
            {(id) => <Input id={id} mono value={form.scopes} onChange={(e) => setForm({ ...form, scopes: e.target.value })} />}
          </Field>
        </div>
        <FormErrors message={error?.message} details={error?.details} />
      </form>
    </Modal>
  );
}

export function ApiKeysPage() {
  const keys = useApiKeys();
  const revoke = useRevokeApiKey();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<ApiKeyRecord | null>(null);

  return (
    <>
      <PageHeader
        title="API keys"
        description="Credentials for services and partners. Keys are 256-bit, stored only as SHA-256 hashes, and can be revoked instantly."
        actions={
          <Button variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
            Create key
          </Button>
        }
      />

      <Card>
        {keys.isLoading && <TableSkeleton />}
        {keys.isError && <ErrorState error={keys.error} onRetry={() => keys.refetch()} />}
        {keys.data?.length === 0 && (
          <EmptyState
            icon={<KeyRound className="h-5 w-5" />}
            title="No API keys yet"
            description="Create a key to let a backend service call your APIs without a user token."
            action={<Button variant="primary" onClick={() => setCreating(true)}>Create the first key</Button>}
          />
        )}
        {keys.data && keys.data.length > 0 && (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Key</Th>
                <Th>Tenant</Th>
                <Th>Plan</Th>
                <Th>Scopes</Th>
                <Th>Created</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {keys.data.map((key) => (
                <Tr key={key.id}>
                  <Td className="font-medium">{key.name}</Td>
                  <Td className="whitespace-nowrap font-mono text-[12.5px] text-ink/70">{key.prefix}…</Td>
                  <Td className="font-mono">{key.tenantId}</Td>
                  <Td><Badge tone={key.tier === 'ENTERPRISE' ? 'solid' : 'accent'}>{key.tier}</Badge></Td>
                  <Td>
                    <div className="flex flex-wrap gap-1">
                      {key.scopes.length ? key.scopes.map((s) => <Badge key={s} mono>{s}</Badge>) : <span className="text-ink/40">none</span>}
                    </div>
                  </Td>
                  <Td className="whitespace-nowrap text-ink/60" title={formatDateTime(key.createdAt)}>
                    {relativeTime(key.createdAt)} · {key.createdBy}
                  </Td>
                  <Td className="text-right">
                    <Button size="sm" variant="danger" onClick={() => setRevoking(key)}>Revoke</Button>
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <div className="mt-5 flex items-start gap-3 rounded-xl bg-white p-4 text-[13px] text-ink/65 shadow-card ring-1 ring-ink/[0.07]">
        <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-accent" />
        API keys get the ROLE_SERVICE role and their scopes only. They can call routed APIs but never the admin API.
      </div>

      {creating && <CreateKeyModal open onClose={() => setCreating(false)} />}
      <ConfirmDialog
        open={Boolean(revoking)}
        title={`Revoke ${revoking?.name}?`}
        description={`Clients using ${revoking?.prefix}… will get 401 Unauthorized on their next request.`}
        confirmLabel="Revoke key"
        loading={revoke.isPending}
        onClose={() => setRevoking(null)}
        onConfirm={() =>
          revoking &&
          revoke.mutate(revoking.id, {
            onSuccess: () => {
              toast.success(`Key ${revoking.name} revoked.`);
              setRevoking(null);
            },
            onError: (e) => toast.error(e.message),
          })
        }
      />
    </>
  );
}
