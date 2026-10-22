import { describe, expect, it, vi, afterEach } from 'vitest';
import { ApiError, api, configureApi, send, toCurl } from './api';
import { decodeJwt, hasAdminRole, isExpired } from './jwt';
import { toRatePoint } from './liveMetrics';
import { formatPercent, formatRate, scopeList, toCsv } from './format';
import { ADMIN_CLAIMS, fakeJwt, jsonResponse } from '../test/helpers';
import type { LiveSnapshot } from './types';

afterEach(() => vi.restoreAllMocks());

describe('jwt', () => {
  it('decodes claims and detects the admin role', () => {
    const claims = decodeJwt(fakeJwt(ADMIN_CLAIMS));
    expect(claims?.sub).toBe('alice');
    expect(hasAdminRole(claims)).toBe(true);
    expect(isExpired(claims!)).toBe(false);
  });

  it('rejects malformed tokens and flags expiry', () => {
    expect(decodeJwt('not-a-token')).toBeNull();
    expect(isExpired({ sub: 'x', exp: 1 })).toBe(true);
    expect(hasAdminRole({ sub: 'x', roles: ['ROLE_USER'] })).toBe(false);
  });
});

describe('api client', () => {
  it('attaches the session token and parses JSON', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }));
    configureApi({ getToken: () => 'tok', onUnauthorized: () => {} });

    const result = await send<{ ok: boolean }>('GET', '/admin/v1/overview');

    expect(result.data).toEqual({ ok: true });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
  });

  it('sends API keys in the configured header instead of a bearer token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({}));
    await send('GET', '/x', { credentials: { kind: 'apiKey', key: 'omk_123', header: 'X-API-Key' } });
    const headers = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers['X-API-Key']).toBe('omk_123');
    expect(headers.Authorization).toBeUndefined();
  });

  it('turns gateway error bodies into ApiError and signs out on 401', async () => {
    const onUnauthorized = vi.fn();
    configureApi({ getToken: () => 'tok', onUnauthorized });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ status: 400, message: 'Invalid route definition', details: ['unknown filter'] }, 400),
    );
    await expect(api.post('/admin/v1/routes', {})).rejects.toMatchObject({
      status: 400,
      message: 'Invalid route definition',
      details: ['unknown filter'],
    });

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ status: 401 }, 401));
    await expect(api.get('/admin/v1/routes')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('builds a shell-safe curl command', () => {
    const curl = toCurl('POST', "http://gw/it's", { Authorization: 'Bearer t' }, '{"a":1}');
    expect(curl).toContain("curl -X POST 'http://gw/it'\\''s'");
    expect(curl).toContain("-H 'Authorization: Bearer t'");
  });
});

describe('live metrics', () => {
  const snapshot = (timestamp: number, requests: number, rateLimited: number, latency: number, count: number): LiveSnapshot => ({
    timestamp, requests, rateLimited, status2xx: 0, status4xx: 0, status5xx: 0,
    latencyTotalMs: latency, latencyCount: count, topRoutes: [],
  });

  it('converts cumulative counters into per-second rates', () => {
    const point = toRatePoint(snapshot(0, 100, 10, 1000, 100), snapshot(2000, 160, 14, 1600, 160));
    expect(point.requestsPerSec).toBe(30);
    expect(point.rateLimitedPerSec).toBe(2);
    expect(point.avgLatencyMs).toBe(10);
  });

  it('never reports negative rates after a gateway restart', () => {
    const point = toRatePoint(snapshot(0, 500, 50, 0, 0), snapshot(1000, 3, 0, 0, 0));
    expect(point.requestsPerSec).toBe(0);
    expect(point.avgLatencyMs).toBeNull();
  });
});

describe('format', () => {
  it('normalises scopes from every metadata shape', () => {
    expect(scopeList('read write')).toEqual(['read', 'write']);
    expect(scopeList(['a'])).toEqual(['a']);
    expect(scopeList({ 0: 'x', 1: 'y' })).toEqual(['x', 'y']);
    expect(scopeList(undefined)).toEqual([]);
  });

  it('formats numbers and escapes CSV', () => {
    expect(formatRate(0)).toBe('0');
    expect(formatRate(2.345)).toBe('2.3');
    expect(formatPercent(-1)).toBe('–');
    expect(toCsv([['a"b', 1]])).toBe('"a""b","1"');
  });
});
