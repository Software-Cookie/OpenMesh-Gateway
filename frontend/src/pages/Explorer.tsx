import { useEffect, useMemo, useState, type FormEvent } from 'react';
import clsx from 'clsx';
import { History, Plus, Send, Trash2, X } from 'lucide-react';
import { resolveHeaders, send, toCurl, type Credentials, type HttpResult } from '../lib/api';
import { formatBytes } from '../lib/format';
import { useConsoleConfig, useRoutes } from '../lib/queries';
import { storage } from '../lib/storage';
import { Card, CardHeader, PageHeader } from '../components/ui/Card';
import { Badge, StatusBadge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { CodeBlock, CopyButton } from '../components/ui/CodeBlock';
import { Field, Input, SegmentedControl, Select, Textarea } from '../components/ui/Form';

const HISTORY_KEY = 'openmesh.console.explorer-history';
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const GATEWAY_HEADERS = ['x-ratelimit-limit', 'x-ratelimit-remaining', 'x-ratelimit-reset', 'x-ratelimit-tier', 'retry-after', 'x-trace-id', 'x-request-id'];

const PRESETS = [
  { label: 'Alpha echo', method: 'GET', path: '/service-alpha/orders' },
  { label: 'Beta service', method: 'GET', path: '/service-beta/inventory' },
  { label: 'Upstream error (502)', method: 'GET', path: '/service-beta/chaos/error?status=500' },
  { label: 'Slow upstream (504)', method: 'GET', path: '/service-beta/chaos/delay?ms=2500' },
  { label: 'Public route', method: 'GET', path: '/public/status' },
];

type AuthMode = 'session' | 'bearer' | 'apiKey' | 'none';

interface HeaderRow {
  id: number;
  name: string;
  value: string;
}

interface HistoryEntry {
  method: string;
  path: string;
  status: number;
  durationMs: number;
  at: number;
}

let rowId = 0;
const newRow = (name = '', value = ''): HeaderRow => ({ id: ++rowId, name, value });

export function ExplorerPage() {
  const { data: config } = useConsoleConfig();
  const { data: routes } = useRoutes();
  const [method, setMethod] = useState<string>('GET');
  const [path, setPath] = useState('/service-alpha/orders');
  const [authMode, setAuthMode] = useState<AuthMode>('session');
  const [credential, setCredential] = useState('');
  const [headers, setHeaders] = useState<HeaderRow[]>([]);
  const [body, setBody] = useState('{\n  "item": "demo"\n}');
  const [result, setResult] = useState<HttpResult | null>(null);
  const [networkError, setNetworkError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [tab, setTab] = useState<'body' | 'headers'>('body');
  const [history, setHistory] = useState<HistoryEntry[]>(() => storage.getJson(HISTORY_KEY, []));

  useEffect(() => storage.setJson(HISTORY_KEY, history), [history]);

  const pathSuggestions = useMemo(
    () =>
      (routes ?? [])
        .flatMap((r) => r.predicates)
        .filter((p) => p.startsWith('Path='))
        .map((p) => p.slice(5).split(',')[0].replace('/**', '/'))
        .filter((p, i, all) => all.indexOf(p) === i),
    [routes],
  );

  const hasBody = method !== 'GET' && method !== 'DELETE';
  const credentials: Credentials =
    authMode === 'bearer'
      ? { kind: 'bearer', token: credential }
      : authMode === 'apiKey'
        ? { kind: 'apiKey', key: credential, header: config?.apiKeyHeader }
        : { kind: authMode };
  const extraHeaders = Object.fromEntries(headers.filter((h) => h.name.trim()).map((h) => [h.name.trim(), h.value]));

  const curl = toCurl(
    method,
    `${window.location.origin}${path}`,
    { ...resolveHeaders({ credentials, headers: extraHeaders, rawBody: hasBody ? body : undefined }) },
    hasBody ? body : undefined,
  );

  async function submit(event?: FormEvent) {
    event?.preventDefault();
    if (!path.startsWith('/')) {
      setNetworkError('Path must start with "/"');
      return;
    }
    setSending(true);
    setNetworkError(null);
    try {
      const response = await send(method, path, { credentials, headers: extraHeaders, rawBody: hasBody ? body : undefined });
      setResult(response);
      setHistory((h) => [{ method, path, status: response.status, durationMs: response.durationMs, at: Date.now() }, ...h].slice(0, 15));
    } catch (e) {
      setResult(null);
      setNetworkError(e instanceof Error ? e.message : 'Network error');
    } finally {
      setSending(false);
    }
  }

  function addForgedHeaders() {
    setHeaders((rows) => [
      ...rows.filter((r) => r.name.trim()),
      newRow('X-User-Id', 'attacker'),
      newRow('X-Tenant-Id', 'someone-elses-tenant'),
      newRow('X-Gateway-Verified', 'true'),
    ]);
  }

  const gatewayHeaders = result ? GATEWAY_HEADERS.map((h) => [h, result.headers.get(h)] as const).filter(([, v]) => v) : [];
  const allHeaders = result ? [...result.headers.entries()] : [];

  return (
    <>
      <PageHeader
        title="API explorer"
        description="Send requests through the full gateway pipeline and inspect exactly what comes back: status, rate-limit headers, trace id and body."
      />

      <div className="grid gap-5 xl:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <div className="space-y-5">
          <Card>
            <form onSubmit={submit} onKeyDown={(e) => (e.ctrlKey || e.metaKey) && e.key === 'Enter' && void submit()}>
              <div className="flex gap-2 border-b border-ink/[0.07] p-4">
                <Select value={method} onChange={(e) => setMethod(e.target.value)} className="w-28 font-mono text-[13px]" aria-label="Method">
                  {METHODS.map((m) => (
                    <option key={m}>{m}</option>
                  ))}
                </Select>
                <Input value={path} onChange={(e) => setPath(e.target.value)} mono list="path-suggestions" aria-label="Path" placeholder="/service-alpha/orders" />
                <datalist id="path-suggestions">
                  {pathSuggestions.map((p) => (
                    <option key={p} value={p} />
                  ))}
                </datalist>
                <Button type="submit" variant="primary" loading={sending} icon={<Send className="h-4 w-4" />}>
                  Send
                </Button>
              </div>

              <div className="flex flex-wrap gap-1.5 border-b border-ink/[0.07] px-4 py-3">
                {PRESETS.map((preset) => (
                  <button
                    key={preset.path}
                    type="button"
                    onClick={() => {
                      setMethod(preset.method);
                      setPath(preset.path);
                    }}
                    className={clsx(
                      'rounded-full px-2.5 py-1 text-xs font-medium ring-1 ring-inset transition-colors',
                      path === preset.path ? 'bg-accent/10 text-accent-strong ring-accent/30' : 'text-ink/65 ring-ink/15 hover:bg-ink/[0.04]',
                    )}
                  >
                    {preset.label}
                  </button>
                ))}
              </div>

              <div className="space-y-4 p-4">
                <div>
                  <p className="mb-1.5 text-[13px] font-medium text-ink/80">Credentials</p>
                  <SegmentedControl
                    value={authMode}
                    onChange={setAuthMode}
                    options={[
                      { value: 'session', label: 'My session' },
                      { value: 'bearer', label: 'Bearer token' },
                      { value: 'apiKey', label: 'API key' },
                      { value: 'none', label: 'None' },
                    ]}
                  />
                </div>
                {(authMode === 'bearer' || authMode === 'apiKey') && (
                  <Field label={authMode === 'apiKey' ? 'API key' : 'Bearer token'}>
                    {(id) => <Textarea id={id} mono rows={2} value={credential} onChange={(e) => setCredential(e.target.value)} placeholder={authMode === 'apiKey' ? 'omk_…' : 'eyJ…'} />}
                  </Field>
                )}

                <div>
                  <div className="mb-1.5 flex items-center justify-between">
                    <p className="text-[13px] font-medium text-ink/80">Headers</p>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" onClick={addForgedHeaders}>
                        Add forged identity
                      </Button>
                      <Button size="sm" variant="ghost" icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setHeaders((rows) => [...rows, newRow()])}>
                        Header
                      </Button>
                    </div>
                  </div>
                  {headers.length === 0 && <p className="text-xs text-ink/45">No custom headers.</p>}
                  <div className="space-y-2">
                    {headers.map((row) => (
                      <div key={row.id} className="flex gap-2">
                        <Input mono value={row.name} placeholder="Header" aria-label="Header name"
                          onChange={(e) => setHeaders((rows) => rows.map((r) => (r.id === row.id ? { ...r, name: e.target.value } : r)))} />
                        <Input mono value={row.value} placeholder="Value" aria-label="Header value"
                          onChange={(e) => setHeaders((rows) => rows.map((r) => (r.id === row.id ? { ...r, value: e.target.value } : r)))} />
                        <Button size="sm" variant="ghost" aria-label="Remove header" className="h-auto" onClick={() => setHeaders((rows) => rows.filter((r) => r.id !== row.id))}>
                          <X className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    ))}
                  </div>
                </div>

                {hasBody && (
                  <Field label="Body" hint="JSON">
                    {(id) => <Textarea id={id} mono rows={5} value={body} onChange={(e) => setBody(e.target.value)} />}
                  </Field>
                )}
                <p className="text-xs text-ink/45">Tip: press Ctrl + Enter to send.</p>
              </div>
            </form>
          </Card>

          <Card>
            <CardHeader title="cURL" actions={<CopyButton value={curl} />} />
            <div className="p-4">
              <CodeBlock value={curl} maxHeight={180} />
            </div>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader
              title="Response"
              actions={
                result && (
                  <div className="flex items-center gap-2">
                    <Badge mono>{result.durationMs} ms</Badge>
                    <Badge mono>{formatBytes(result.sizeBytes)}</Badge>
                    <StatusBadge status={result.status} text={result.statusText} />
                  </div>
                )
              }
            />
            {!result && !networkError && (
              <p className="px-5 py-16 text-center text-[13px] text-ink/50">Send a request to see the response here.</p>
            )}
            {networkError && <p className="px-5 py-10 text-center text-sm font-medium">{networkError}</p>}
            {result && (
              <div className="space-y-4 p-5">
                {gatewayHeaders.length > 0 && (
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-3 rounded-lg bg-accent/[0.05] p-4 ring-1 ring-inset ring-accent/15 sm:grid-cols-3">
                    {gatewayHeaders.map(([name, value]) => (
                      <div key={name} className="min-w-0">
                        <dt className="truncate font-mono text-[11px] text-ink/50">{name}</dt>
                        <dd className="truncate font-mono text-[13px] font-medium text-accent-strong" title={value ?? ''}>
                          {value}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
                <SegmentedControl
                  value={tab}
                  onChange={setTab}
                  options={[
                    { value: 'body', label: 'Body' },
                    { value: 'headers', label: `Headers (${allHeaders.length})` },
                  ]}
                />
                {tab === 'body' ? (
                  <CodeBlock value={result.data ?? result.text} maxHeight={520} />
                ) : (
                  <dl className="divide-y divide-ink/[0.06] rounded-lg ring-1 ring-ink/[0.08]">
                    {allHeaders.map(([name, value]) => (
                      <div key={name} className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-3 px-3 py-2">
                        <dt className="truncate font-mono text-xs text-ink/55">{name}</dt>
                        <dd className="break-all font-mono text-xs">{value}</dd>
                      </div>
                    ))}
                  </dl>
                )}
              </div>
            )}
          </Card>

          <Card>
            <CardHeader
              title={<span className="flex items-center gap-2"><History className="h-4 w-4 text-ink/50" /> History</span>}
              actions={history.length > 0 && (
                <Button size="sm" variant="ghost" icon={<Trash2 className="h-3.5 w-3.5" />} onClick={() => setHistory([])}>
                  Clear
                </Button>
              )}
            />
            {history.length === 0 ? (
              <p className="px-5 py-6 text-center text-[13px] text-ink/45">Requests you send appear here.</p>
            ) : (
              <ul className="divide-y divide-ink/[0.06]">
                {history.map((entry) => (
                  <li key={entry.at}>
                    <button
                      type="button"
                      onClick={() => {
                        setMethod(entry.method);
                        setPath(entry.path);
                      }}
                      className="flex w-full items-center gap-3 px-5 py-2.5 text-left hover:bg-ink/[0.02]"
                    >
                      <span className="w-14 shrink-0 font-mono text-xs font-semibold text-ink/60">{entry.method}</span>
                      <span className="min-w-0 flex-1 truncate font-mono text-[13px]">{entry.path}</span>
                      <span className="text-xs text-ink/45">{entry.durationMs} ms</span>
                      <StatusBadge status={entry.status} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}
