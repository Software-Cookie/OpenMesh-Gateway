import type { JwtClaims } from './types';

/** Decodes a JWT payload for display. The gateway, not the browser, verifies signatures. */
export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.trim().split('.');
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const json = decodeURIComponent(
      Array.from(atob(padded), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    );
    const claims = JSON.parse(json) as JwtClaims;
    return typeof claims === 'object' && claims !== null && typeof claims.sub === 'string' ? claims : null;
  } catch {
    return null;
  }
}

export function isExpired(claims: JwtClaims, now = Date.now()): boolean {
  return typeof claims.exp === 'number' && claims.exp * 1000 <= now;
}

export function hasAdminRole(claims: JwtClaims | null): boolean {
  const roles = claims?.roles;
  return Array.isArray(roles) && (roles.includes('ROLE_ADMIN') || roles.includes('ADMIN'));
}
