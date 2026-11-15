import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { configureApi, send } from './api';
import { decodeJwt, isExpired } from './jwt';
import { storage } from './storage';
import type { ApiErrorBody, JwtClaims, Session } from './types';

const TOKEN_KEY = 'openmesh.console.token';
const SESSION_KEY = 'openmesh.console.session';

interface AuthState {
  token: string | null;
  claims: JwtClaims | null;
  /** The operator as the gateway resolved them (works with any identity provider's claim layout). */
  session: Session | null;
  /** Asks the gateway to verify the token and its admin role; returns an error message instead of throwing. */
  signIn: (token: string) => Promise<string | null>;
  signOut: (reason?: string) => void;
  signOutReason: string | null;
}

const AuthContext = createContext<AuthState | null>(null);

function readStored(): { token: string | null; session: Session | null } {
  const token = storage.get(TOKEN_KEY);
  const session = storage.getJson<Session | null>(SESSION_KEY, null);
  if (!token || !session) return { token: null, session: null };
  const claims = decodeJwt(token);
  return claims && !isExpired(claims) ? { token, session } : { token: null, session: null };
}

/** The gateway is the only judge of a token: signature, issuer, audience, expiry and ROLE_ADMIN. */
async function verifyWithGateway(token: string): Promise<{ session: Session } | { error: string }> {
  try {
    const result = await send<Session & Partial<ApiErrorBody>>('GET', '/admin/v1/session', {
      credentials: { kind: 'bearer', token },
    });
    if (result.ok && result.data) return { session: result.data };
    if (result.status === 401) return { error: 'The gateway rejected this token: check its signature, issuer, audience and expiry.' };
    if (result.status === 403) return { error: 'This token is valid but does not have the ROLE_ADMIN role.' };
    return { error: result.data?.message ?? `The gateway could not verify the token (HTTP ${result.status}).` };
  } catch {
    return { error: 'The gateway is not reachable. Is it running?' };
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [stored] = useState(readStored);
  const [token, setToken] = useState<string | null>(stored.token);
  const [session, setSession] = useState<Session | null>(stored.session);
  const [signOutReason, setSignOutReason] = useState<string | null>(null);
  const claims = useMemo(() => (token ? decodeJwt(token) : null), [token]);

  const signOut = useCallback(
    (reason?: string) => {
      storage.set(TOKEN_KEY, null);
      storage.set(SESSION_KEY, null);
      setToken(null);
      setSession(null);
      setSignOutReason(reason ?? null);
      queryClient.clear();
    },
    [queryClient],
  );

  const signIn = useCallback(async (candidate: string) => {
    const trimmed = candidate.trim();
    const decoded = decodeJwt(trimmed);
    if (!decoded) return 'That does not look like a JWT (header.payload.signature).';
    if (isExpired(decoded)) return 'That token has expired.';
    const verified = await verifyWithGateway(trimmed);
    if ('error' in verified) return verified.error;
    storage.set(TOKEN_KEY, trimmed);
    storage.setJson(SESSION_KEY, verified.session);
    setSignOutReason(null);
    setSession(verified.session);
    setToken(trimmed);
    return null;
  }, []);

  // Kept in refs and wired during render: child components' effects run before this provider's
  // effects, so registering in useEffect would let the first API calls go out without a token.
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const signOutRef = useRef(signOut);
  signOutRef.current = signOut;
  configureApi({
    getToken: () => tokenRef.current,
    onUnauthorized: () => signOutRef.current('Your session expired. Sign in again.'),
  });

  // Sign out automatically when the token expires while the console is open.
  useEffect(() => {
    if (!claims?.exp) return;
    const ms = claims.exp * 1000 - Date.now();
    const timer = window.setTimeout(() => signOut('Your session expired. Sign in again.'), Math.max(ms, 0));
    return () => window.clearTimeout(timer);
  }, [claims, signOut]);

  const value = useMemo<AuthState>(
    () => ({ token, claims, session, signIn, signOut, signOutReason }),
    [token, claims, session, signIn, signOut, signOutReason],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
