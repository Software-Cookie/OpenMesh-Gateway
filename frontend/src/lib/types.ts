// Shapes returned by the gateway's admin API.

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  error?: string;
  reason?: string;
  message?: string;
  path?: string;
  traceId?: string;
  retryAfterSeconds?: number;
  details?: string[];
}

export interface ConsoleConfig {
  devIssuerEnabled: boolean;
  apiKeyHeader: string;
  version: string;
}

export interface RateLimitPolicy {
  enabled?: boolean | string;
  replenishRate?: number | string;
  burstCapacity?: number | string;
}

export interface RouteDefinition {
  id: string;
  uri: string;
  predicates: string[];
  filters: string[];
  order: number;
  metadata: {
    requiredScopes?: string | string[] | Record<string, string>;
    rateLimit?: RateLimitPolicy;
    [key: string]: unknown;
  };
  source?: 'static' | 'dynamic';
}

export type Tier = 'FREE' | 'PRO' | 'ENTERPRISE';

export interface ApiKeyRecord {
  id: string;
  name: string;
  tenantId: string;
  tier: Tier;
  scopes: string[];
  prefix: string;
  createdAt: string;
  createdBy: string;
}

export interface CreatedApiKey {
  apiKey: string;
  record: ApiKeyRecord;
}

export type BreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'FORCED_OPEN' | 'DISABLED' | 'METRICS_ONLY';

export interface CircuitBreaker {
  name: string;
  state: BreakerState;
  failureRate: number;
  slowCallRate: number;
  bufferedCalls: number;
  failedCalls: number;
  notPermittedCalls: number;
}

export interface Overview {
  staticRoutes: number;
  dynamicRoutes: number;
  apiKeys: number;
  requestsToday: number;
  rejectedToday: number;
  activeTenantsToday: number;
  redis: 'UP' | 'DOWN';
  circuitBreakers: CircuitBreaker[];
}

export interface TenantUsage {
  tenantId: string;
  requests: number;
  rejected: number;
}

export interface UsageReport {
  date: string;
  tenants: TenantUsage[];
}

export interface AuditEvent {
  timestamp: string;
  actor: string;
  action: string;
  target: string;
  detail?: string;
}

export interface RouteTraffic {
  route: string;
  requests: number;
  errors: number;
}

export interface LiveSnapshot {
  timestamp: number;
  requests: number;
  status2xx: number;
  status4xx: number;
  status5xx: number;
  rateLimited: number;
  latencyTotalMs: number;
  latencyCount: number;
  topRoutes: RouteTraffic[];
}

export interface TokenResponse {
  token: string;
  expiresAt: string;
  tenantId: string;
  userId: string;
  roles: string[];
  scopes: string[];
  tier: Tier;
}

export interface JwtClaims {
  sub: string;
  iss?: string;
  exp?: number;
  tenant_id?: string;
  tier?: string;
  scope?: string;
  roles?: string[];
  [key: string]: unknown;
}
