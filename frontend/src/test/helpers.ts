/** Builds an unsigned JWT with the given claims (the browser never verifies signatures). */
export function fakeJwt(claims: Record<string, unknown>): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode(claims)}.signature`;
}

export const ADMIN_CLAIMS = {
  sub: 'alice',
  tenant_id: 'tenant-acme',
  roles: ['ROLE_ADMIN'],
  exp: Math.floor(Date.now() / 1000) + 3600,
};

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}
