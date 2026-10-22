import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, send } from './api';
import type {
  ApiKeyRecord,
  AuditEvent,
  CircuitBreaker,
  ConsoleConfig,
  CreatedApiKey,
  Overview,
  RouteDefinition,
  Tier,
  UsageReport,
} from './types';

export const keys = {
  config: ['config'] as const,
  health: ['health'] as const,
  overview: ['overview'] as const,
  routes: ['routes'] as const,
  apiKeys: ['api-keys'] as const,
  breakers: ['circuit-breakers'] as const,
  usage: (date: string) => ['usage', date] as const,
  audit: ['audit'] as const,
};

export function useConsoleConfig() {
  return useQuery({
    queryKey: keys.config,
    queryFn: async () => {
      const result = await send<ConsoleConfig>('GET', '/console/config', { credentials: { kind: 'none' } });
      return result.data ?? { devIssuerEnabled: false, apiKeyHeader: 'X-API-Key', version: 'unknown' };
    },
    staleTime: Infinity,
  });
}

export function useHealth() {
  return useQuery({
    queryKey: keys.health,
    queryFn: async () => {
      const result = await send<{ status: string }>('GET', '/actuator/health', { credentials: { kind: 'none' } });
      return result.data?.status ?? 'UNKNOWN';
    },
    refetchInterval: 15_000,
    retry: false,
  });
}

export function useOverview() {
  return useQuery({ queryKey: keys.overview, queryFn: () => api.get<Overview>('/admin/v1/overview'), refetchInterval: 10_000 });
}

export function useRoutes() {
  return useQuery({ queryKey: keys.routes, queryFn: () => api.get<RouteDefinition[]>('/admin/v1/routes') });
}

export function useApiKeys() {
  return useQuery({ queryKey: keys.apiKeys, queryFn: () => api.get<ApiKeyRecord[]>('/admin/v1/api-keys') });
}

export function useCircuitBreakers(refetchInterval: number | false = 3000) {
  return useQuery({
    queryKey: keys.breakers,
    queryFn: () => api.get<CircuitBreaker[]>('/admin/v1/circuit-breakers'),
    refetchInterval,
  });
}

export function useUsage(date: string) {
  return useQuery({
    queryKey: keys.usage(date),
    queryFn: () => api.get<UsageReport>(`/admin/v1/usage${date ? `?date=${encodeURIComponent(date)}` : ''}`),
    refetchInterval: 15_000,
  });
}

export function useAudit() {
  return useQuery({ queryKey: keys.audit, queryFn: () => api.get<AuditEvent[]>('/admin/v1/audit?limit=200') });
}

/** Invalidates everything a route or key change can affect. */
function useInvalidateAdmin() {
  const client = useQueryClient();
  return () => {
    void client.invalidateQueries({ queryKey: keys.routes });
    void client.invalidateQueries({ queryKey: keys.apiKeys });
    void client.invalidateQueries({ queryKey: keys.overview });
    void client.invalidateQueries({ queryKey: keys.audit });
  };
}

export function useSaveRoute() {
  const invalidate = useInvalidateAdmin();
  return useMutation({
    mutationFn: ({ route, existingId }: { route: Omit<RouteDefinition, 'source'>; existingId?: string }) =>
      existingId
        ? api.put<RouteDefinition>(`/admin/v1/routes/${encodeURIComponent(existingId)}`, route)
        : api.post<RouteDefinition>('/admin/v1/routes', route),
    onSuccess: invalidate,
  });
}

export function useDeleteRoute() {
  const invalidate = useInvalidateAdmin();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/admin/v1/routes/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  });
}

export function useReloadRoutes() {
  const invalidate = useInvalidateAdmin();
  return useMutation({ mutationFn: () => api.post('/admin/v1/routes/refresh'), onSuccess: invalidate });
}

export function useCreateApiKey() {
  const invalidate = useInvalidateAdmin();
  return useMutation({
    mutationFn: (body: { name: string; tenantId: string; tier: Tier; scopes: string[] }) =>
      api.post<CreatedApiKey>('/admin/v1/api-keys', body),
    onSuccess: invalidate,
  });
}

export function useRevokeApiKey() {
  const invalidate = useInvalidateAdmin();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/admin/v1/api-keys/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  });
}
