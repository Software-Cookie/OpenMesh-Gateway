import type { ApiErrorBody } from './types';

/** How a request authenticates. */
export type Credentials =
  | { kind: 'session' }
  | { kind: 'none' }
  | { kind: 'bearer'; token: string }
  | { kind: 'apiKey'; key: string; header?: string };

export interface SendOptions {
  body?: unknown;
  rawBody?: string;
  credentials?: Credentials;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/** A completed HTTP exchange, successful or not. Used by the explorer and the live demos. */
export interface HttpResult<T = unknown> {
  ok: boolean;
  status: number;
  statusText: string;
  headers: Headers;
  data: T | null;
  text: string;
  durationMs: number;
  sizeBytes: number;
}

/** Error thrown by admin calls; carries the gateway's structured error body. */
export class ApiError extends Error {
  readonly status: number;
  readonly reason?: string;
  readonly details: string[];
  readonly traceId?: string;

  constructor(status: number, body: Partial<ApiErrorBody> | null, fallback: string) {
    super(body?.message || fallback);
    this.name = 'ApiError';
    this.status = status;
    this.reason = body?.reason;
    this.details = body?.details ?? [];
    this.traceId = body?.traceId;
  }
}

let tokenProvider: () => string | null = () => null;
let unauthorizedHandler: () => void = () => {};

export function configureApi(options: { getToken: () => string | null; onUnauthorized: () => void }) {
  tokenProvider = options.getToken;
  unauthorizedHandler = options.onUnauthorized;
}

function buildHeaders(options: SendOptions): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined || options.rawBody !== undefined) {
    headers['Content-Type'] = headers['Content-Type'] ?? 'application/json';
  }
  const credentials = options.credentials ?? { kind: 'session' };
  if (credentials.kind === 'session') {
    const token = tokenProvider();
    if (token) headers.Authorization = `Bearer ${token}`;
  } else if (credentials.kind === 'bearer' && credentials.token.trim()) {
    headers.Authorization = `Bearer ${credentials.token.trim()}`;
  } else if (credentials.kind === 'apiKey' && credentials.key.trim()) {
    headers[credentials.header ?? 'X-API-Key'] = credentials.key.trim();
  }
  return headers;
}

/** Sends any request and never throws on HTTP errors (only on network failure). */
export async function send<T = unknown>(method: string, path: string, options: SendOptions = {}): Promise<HttpResult<T>> {
  const started = performance.now();
  const response = await fetch(path, {
    method,
    headers: buildHeaders(options),
    body: options.rawBody ?? (options.body !== undefined ? JSON.stringify(options.body) : undefined),
    signal: options.signal,
  });
  const text = await response.text();
  const durationMs = Math.round(performance.now() - started);
  let data: T | null = null;
  if (text) {
    try {
      data = JSON.parse(text) as T;
    } catch {
      data = null;
    }
  }
  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
    data,
    text,
    durationMs,
    sizeBytes: new Blob([text]).size,
  };
}

/** Admin API call with the signed-in session; throws ApiError on any non-2xx response. */
async function admin<T>(method: string, path: string, body?: unknown): Promise<T> {
  const result = await send<T>(method, path, { body });
  if (!result.ok) {
    if (result.status === 401) unauthorizedHandler();
    const errorBody = result.data as Partial<ApiErrorBody> | null;
    const fallback = result.status === 403 ? 'Your token does not have the ROLE_ADMIN role' : `Request failed (HTTP ${result.status})`;
    throw new ApiError(result.status, errorBody, fallback);
  }
  return result.data as T;
}

export const api = {
  get: <T>(path: string) => admin<T>('GET', path),
  post: <T>(path: string, body?: unknown) => admin<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => admin<T>('PUT', path, body),
  delete: <T = void>(path: string) => admin<T>('DELETE', path),
};

/** Builds a copy-pasteable curl command for a request. */
export function toCurl(method: string, url: string, headers: Record<string, string>, body?: string): string {
  const parts = [`curl -X ${method} '${url.replace(/'/g, "'\\''")}'`];
  for (const [name, value] of Object.entries(headers)) {
    parts.push(`-H '${name}: ${value.replace(/'/g, "'\\''")}'`);
  }
  if (body) parts.push(`--data '${body.replace(/'/g, "'\\''")}'`);
  return parts.join(' \\\n  ');
}

export { buildHeaders as resolveHeaders };
