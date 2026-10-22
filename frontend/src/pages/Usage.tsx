import { useState } from 'react';
import { BarChart3, Download } from 'lucide-react';
import { downloadFile, formatNumber, formatPercent, toCsv } from '../lib/format';
import { useUsage } from '../lib/queries';
import { Card, CardHeader, PageHeader } from '../components/ui/Card';
import { BarList, Legend } from '../components/ui/Charts';
import { Button } from '../components/ui/Button';
import { EmptyState, ErrorState, TableSkeleton } from '../components/ui/Feedback';
import { Input } from '../components/ui/Form';
import { Table, Td, Th, Tr } from '../components/ui/Table';

export function UsagePage() {
  const [date, setDate] = useState('');
  const usage = useUsage(date);
  const tenants = usage.data?.tenants ?? [];
  const totalRequests = tenants.reduce((sum, t) => sum + t.requests, 0);
  const totalRejected = tenants.reduce((sum, t) => sum + t.rejected, 0);
  const day = usage.data?.date ?? date;

  function exportCsv() {
    const rows = [['tenant_id', 'requests', 'rate_limited'], ...tenants.map((t) => [t.tenantId, t.requests, t.rejected])];
    downloadFile(`openmesh-usage-${day}.csv`, toCsv(rows));
  }

  return (
    <>
      <PageHeader
        title="Usage"
        description="Billable requests per tenant per UTC day, aggregated across every gateway instance (updated every 10 seconds)."
        actions={
          <>
            <Input type="date" value={day} onChange={(e) => setDate(e.target.value)} className="w-auto" aria-label="Day" />
            <Button icon={<Download className="h-4 w-4" />} onClick={exportCsv} disabled={!tenants.length}>
              Export CSV
            </Button>
          </>
        }
      />

      <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-4">
        {[
          ['Requests', formatNumber(totalRequests)],
          ['Rate limited', formatNumber(totalRejected)],
          ['Rejection rate', totalRequests ? formatPercent((totalRejected / totalRequests) * 100) : '–'],
          ['Tenants', formatNumber(tenants.length)],
        ].map(([label, value]) => (
          <Card key={label} className="p-4">
            <p className="text-[11.5px] font-semibold uppercase tracking-wider text-ink/50">{label}</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{usage.data ? value : '–'}</p>
          </Card>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <Card>
          <CardHeader
            title="By tenant"
            actions={<Legend series={[{ key: 'ok', label: 'Served', tone: 'accent' }, { key: 'rl', label: 'Rate limited', tone: 'ink' }]} />}
          />
          <div className="p-5">
            <BarList
              emptyText="No usage recorded for this day."
              items={tenants.slice(0, 10).map((t) => ({
                label: t.tenantId,
                value: t.requests,
                secondary: t.rejected,
                hint: formatNumber(t.requests),
              }))}
            />
          </div>
        </Card>

        <Card>
          {usage.isLoading && <TableSkeleton />}
          {usage.isError && <ErrorState error={usage.error} onRetry={() => usage.refetch()} />}
          {usage.data && tenants.length === 0 && (
            <EmptyState icon={<BarChart3 className="h-5 w-5" />} title="No usage for this day" description="Traffic appears here within about 10 seconds." />
          )}
          {tenants.length > 0 && (
            <Table>
              <thead>
                <tr>
                  <Th>Tenant</Th>
                  <Th numeric>Requests</Th>
                  <Th numeric>Rate limited</Th>
                  <Th numeric>Rejection</Th>
                </tr>
              </thead>
              <tbody>
                {tenants.map((t) => (
                  <Tr key={t.tenantId}>
                    <Td className="font-mono">{t.tenantId}</Td>
                    <Td numeric>{formatNumber(t.requests)}</Td>
                    <Td numeric>{formatNumber(t.rejected)}</Td>
                    <Td numeric>{t.requests ? formatPercent((t.rejected / t.requests) * 100) : '–'}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      </div>
    </>
  );
}
