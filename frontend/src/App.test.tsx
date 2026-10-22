import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import { ADMIN_CLAIMS, fakeJwt, jsonResponse } from './test/helpers';

const OVERVIEW = {
  staticRoutes: 3,
  dynamicRoutes: 1,
  apiKeys: 2,
  requestsToday: 1200,
  rejectedToday: 60,
  activeTenantsToday: 4,
  redis: 'UP',
  circuitBreakers: [{ name: 'betaCircuitBreaker', state: 'OPEN', failureRate: 100, slowCallRate: 0, bufferedCalls: 5, failedCalls: 5, notPermittedCalls: 2 }],
};

function mockGateway(devIssuerEnabled = true) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    if (url === '/console/config') return jsonResponse({ devIssuerEnabled, apiKeyHeader: 'X-API-Key', version: '1.0.0' });
    if (url === '/actuator/health') return jsonResponse({ status: 'UP' });
    if (url === '/auth/token' && init?.method === 'POST') return jsonResponse({ token: fakeJwt(ADMIN_CLAIMS) });
    if (url === '/admin/v1/overview') return jsonResponse(OVERVIEW);
    if (url === '/admin/v1/metrics/live') {
      return jsonResponse({ timestamp: Date.now(), requests: 10, status2xx: 10, status4xx: 0, status5xx: 0, rateLimited: 0, latencyTotalMs: 50, latencyCount: 10, topRoutes: [{ route: 'alpha-service-route', requests: 10, errors: 0 }] });
    }
    return jsonResponse({}, 404);
  });
}

beforeEach(() => {
  window.location.hash = '';
  vi.restoreAllMocks();
});

describe('console', () => {
  it('signs in with the development issuer and shows the dashboard', async () => {
    mockGateway();
    render(<App />);

    const button = await screen.findByRole('button', { name: /sign in as administrator/i });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(await screen.findByText('1.2K')).toBeInTheDocument();
    expect(await screen.findByText('betaCircuitBreaker')).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
  });

  it('refuses tokens without the admin role', async () => {
    mockGateway(false);
    render(<App />);

    const textarea = await screen.findByLabelText(/access token/i);
    await userEvent.type(textarea, fakeJwt({ sub: 'bob', roles: ['ROLE_USER'], exp: ADMIN_CLAIMS.exp }));
    await userEvent.click(screen.getByRole('button', { name: /sign in with token/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('ROLE_ADMIN');
  });
});
