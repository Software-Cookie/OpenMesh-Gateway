import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { configureApi } from './api';
import { decodeJwt, hasAdminRole, isExpired } from './jwt';
import { storage } from './storage';
import type { JwtClaims } from './types';

const TOKEN_KEY = 'openmesh.console.token';

interface AuthState {
  token: string | null;
  claims: JwtClaims | null;
  isAdmin: boolean;
  /** Validates and stores a token; returns an error message instead of throwing. */
  signIn: (token: string) => string | null;
  signOut: (reason?: string) => void;
  signOutReason: string | null;
}

const AuthContext = createContext<AuthState | null>(null);

function readStoredToken(): string | null {
  const token = storage.get(TOKEN_KEY);
  if (!token) return null;
  const claims = decodeJwt(token);
  return claims && !isExpired(claims) ? token : null;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [token, setToken] = useState<string | null>(readStoredToken);
  const [signOutReason, setSignOutReason] = useState<string | null>(null);
  const claims = useMemo(() => (token ? decodeJwt(token) : null), [token]);

  const signOut = useCallback(
    (reason?: string) => {
      storage.set(TOKEN_KEY, null);
      setToken(null);
      setSignOutReason(reason ?? null);
      queryClient.clear();
    },
    [queryClient],
  );

  const signIn = useCallback((candidate: string) => {
    const decoded = decodeJwt(candidate);
    if (!decoded) return 'That does not look like a JWT (header.payload.signature).';
    if (isExpired(decoded)) return 'That token has expired.';
    if (!hasAdminRole(decoded)) return 'This console needs a token with the ROLE_ADMIN role.';
    storage.set(TOKEN_KEY, candidate.trim());
    setSignOutReason(null);
    setToken(candidate.trim());
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
    () => ({ token, claims, isAdmin: hasAdminRole(claims), signIn, signOut, signOutReason }),
    [token, claims, signIn, signOut, signOutReason],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
